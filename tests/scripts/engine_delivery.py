# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
"""Publish Position datums from Python through the Java engine to a Python actor.

Run by ``test_installation.py`` inside the clean installed environment, in its own process.
Kept as a file rather than an embedded string so it stays readable and lintable.

What it proves beyond the codec test: gateways, per-(topic, key) routing, the Java time machine's
event-time ordering, and dispatch. The codec test exercises serialisation only, so all of this
could break while it stayed green.
"""
import threading
import time
from decimal import Decimal

from inventzia.pulse.beacon.core.actor import BeaconActor
from inventzia.pulse.beacon.core.crosslanguage import jpype_host
from inventzia.pulse.beacon.core.crosslanguage.cross_language_streamer import CrossLanguageStreamer
from inventzia.pulse.beacon.core.gateway import BeaconGateway
from inventzia.pulse.finance.schemas.position import Position

KEY = "CL"
START = 1_283_630_000_000
#: 123456789.12 is the value a float path destroys ($2.88 lost), so a regression would be obvious
#: rather than merely imprecise.
ROWS = [
    (START, 100, Decimal("68.47")),
    (START + 1_000, 70, Decimal("68.48")),
    (START + 2_000, -50, Decimal("123456789.12")),
]


class PositionFeed(BeaconGateway):
    """Source gateway emitting a fixed stream of Position datums."""

    def produce(self):
        for when, size, vwap in ROWS:
            yield "finance.positions", Position(
                instrumentId=KEY, positionTime=when, signedPosition=size,
                positionVwap=vwap, positionPoint="ID_TRD")


class Recorder(BeaconActor):
    def __init__(self, name, sink):
        super().__init__(name)
        self._sink = sink

    def on_event(self, topic_name, datum):
        self._sink.append((str(datum.datum_key), int(datum.datum_time),
                           int(datum.signed_position), str(datum.position_vwap)))


def main() -> None:
    jpype_host.start_jvm()                       # no paths: the finance jar is discovered
    from jpype import JClass

    Engine = JClass("com.inventzia.pulse.beacon.core.MultiClientEngine")
    Topic = JClass("com.inventzia.pulse.beacon.core.Topic")
    XActor = JClass("com.inventzia.pulse.beacon.core.crosslanguage.CrossLanguageActor")
    XGateway = JClass("com.inventzia.pulse.beacon.core.crosslanguage.CrossLanguageGateway")
    PositionJ = JClass("com.inventzia.pulse.finance.schemas.Position")
    JList = JClass("java.util.List")
    JMap = JClass("java.util.Map")
    JThread = JClass("java.lang.Thread")

    end = START + 5_000
    topic = Topic("finance.positions", PositionJ.class_)
    engine = Engine("Engine", START, end)
    feed = XGateway("py-position-feed", START, end).withTopic(topic)
    consumer = XActor("py-recorder")
    engine.registerPublisher(feed, topic, JList.of(KEY))
    engine.registerActor(consumer, JMap.of(topic, JList.of(KEY)), JMap.of())

    received: list = []
    errors: dict = {}
    consume = CrossLanguageStreamer(consumer, Recorder("Recorder", received))
    produce = CrossLanguageStreamer(feed, PositionFeed("Feed"))

    def guarded(fn, label, **kwargs):
        def run():
            try:
                fn(**kwargs)
            except BaseException as exc:                      # noqa: BLE001
                errors.setdefault(label, exc)
        return run

    t_consume = threading.Thread(target=guarded(consume.run_consume, "consume"), daemon=True)
    t_consume.start()
    engine_thread = JThread(engine, "Engine-thread")
    engine_thread.start()

    deadline = time.monotonic() + 10
    while str(engine.status().name()) != "STARTED" and time.monotonic() < deadline:
        time.sleep(0.02)

    JThread(feed, "py-feed").start()
    t_produce = threading.Thread(
        target=guarded(produce.run_produce, "produce",
                       on_error=lambda e: errors.setdefault("produce", e)), daemon=True)
    t_produce.start()

    deadline = time.monotonic() + 60
    while engine_thread.isAlive() and time.monotonic() < deadline and not errors:
        time.sleep(0.05)
    for thread in (t_produce, t_consume):
        thread.join(5)

    assert not errors, errors
    status = str(engine.status().name())
    assert status == "COMPLETE", status

    expected = [(KEY, when, size, str(vwap)) for when, size, vwap in ROWS]
    assert received == expected, (received, expected)
    print("ENGINE DELIVERED", len(received), status)


if __name__ == "__main__":
    main()
