package app.pdfa.engine.protocol

import app.pdfa.engine.*
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.test.assertFailsWith

class WireTest {
    @Test fun primitivesRoundTrip() {
        val w = WireWriter(1).u8(255).bool(true).i32(-5).i64(Long.MAX_VALUE).f32(1.5f).f64(-2.25).string("héllo").bytes(byteArrayOf(1, 2, 3))
        val r = WireReader(w.toByteArray())
        assertEquals(255, r.u8()); assertTrue(r.bool()); assertEquals(-5, r.i32()); assertEquals(Long.MAX_VALUE, r.i64())
        assertEquals(1.5f, r.f32(), 0f); assertEquals(-2.25, r.f64(), 0.0); assertEquals("héllo", r.string())
        assertArrayEquals(byteArrayOf(1, 2, 3), r.bytes()); r.finish()
    }

    @Test fun littleEndianLayout() {
        assertArrayEquals(byteArrayOf(4, 3, 2, 1), WireWriter().i32(0x01020304).toByteArray())
        assertArrayEquals(byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1), WireWriter().i64(0x0102030405060708L).toByteArray())
    }

    @Test fun truncatedPrimitivesThrow() {
        assertFailsWith<WireException> { WireReader(ByteArray(0)).u8() }
        assertFailsWith<WireException> { WireReader(ByteArray(3)).i32() }
        assertFailsWith<WireException> { WireReader(ByteArray(7)).i64() }
        assertFailsWith<WireException> { WireReader(ByteArray(3)).f32() }
        assertFailsWith<WireException> { WireReader(ByteArray(7)).f64() }
    }

    @Test fun hugeBlobPrefixThrowsWithoutAllocating() {
        val bytes = WireWriter().i32(0x7FFFFFFF).toByteArray()
        assertFailsWith<WireException> { WireReader(bytes).bytes() }
        assertFailsWith<WireException> { WireReader(bytes).string() }
        assertFailsWith<WireException> { WireReader(bytes).count() }
        assertFailsWith<WireException> { WireReader(bytes).intList() }
    }

    @Test fun negativeLengthsThrow() {
        val bytes = WireWriter().i32(-1).toByteArray()
        assertFailsWith<WireException> { WireReader(bytes).bytes() }
        assertFailsWith<WireException> { WireReader(bytes).string() }
        assertFailsWith<WireException> { WireReader(bytes).count() }
    }

    @Test fun lengthAboveRemainingThrows() {
        val bytes = WireWriter().i32(10).u8(1).toByteArray()
        assertFailsWith<WireException> { WireReader(bytes).bytes() }
    }

    @Test fun blobCapIsEnforcedBeforeRemainingCheck() {
        val bytes = WireWriter().i32(WireLimits.MAX_BLOB_BYTES + 1).toByteArray()
        val e = assertFailsWith<WireException> { WireReader(bytes).bytes() }
        assertTrue(e.message!!.contains("outside"))
    }

    @Test fun listCountCapAndFit() {
        assertFailsWith<WireException> { WireReader(WireWriter().i32(WireLimits.MAX_LIST_ITEMS + 1).toByteArray()).count() }
        assertFailsWith<WireException> { WireReader(WireWriter().i32(1000).toByteArray()).count(4) }
        assertEquals(0, WireReader(WireWriter().i32(0).toByteArray()).count(4))
    }

    @Test fun listOfMillionItemsDeclaredInTinyBufferIsRejected() {
        val bytes = WireWriter().i32(WireLimits.MAX_LIST_ITEMS).toByteArray()
        assertFailsWith<WireException> { WireReader(bytes).list(1) { u8() } }
    }

    @Test fun writerRejectsOversizedList() {
        assertFailsWith<WireException> { WireWriter().count(WireLimits.MAX_LIST_ITEMS + 1) }
        assertFailsWith<WireException> { WireWriter().count(-1) }
    }

    @Test fun finishDetectsTrailingBytes() {
        val r = WireReader(byteArrayOf(1, 2))
        r.u8()
        assertFailsWith<WireException> { r.finish() }
    }

    @Test fun readerWindowIsChecked() {
        assertFailsWith<WireException> { WireReader(ByteArray(4), 2, 9) }
        assertFailsWith<WireException> { WireReader(ByteArray(4), 3, 2) }
        assertEquals(2, WireReader(ByteArray(4), 1, 3).remaining)
    }

    @Test fun emptyStringAndBytes() {
        val r = WireReader(WireWriter().string("").bytes(ByteArray(0)).toByteArray())
        assertEquals("", r.string()); assertEquals(0, r.bytes().size); r.finish()
    }

    @Test fun largeBlobGrowsOnce() {
        val big = ByteArray(5_000_000) { it.toByte() }
        val out = WireWriter().bytes(big).toByteArray()
        assertEquals(big.size + 4, out.size)
        assertArrayEquals(big, WireReader(out).bytes())
    }

    @Test fun unicodeRoundTrip() {
        val s = "漢字😀é"
        assertEquals(s, WireReader(WireWriter().string(s).toByteArray()).string())
    }
}

class FramingTest {
    @Test fun requestRoundTrip() {
        for (op in Op.entries) {
            val r = Request.decode(Request(42L + op.code, op, byteArrayOf(1, 2, 3)).encode())
            assertEquals(op, r.op); assertEquals(42L + op.code, r.requestId); assertArrayEquals(byteArrayOf(1, 2, 3), r.payload)
        }
    }

    @Test fun responseRoundTrip() {
        for (st in ResponseStatus.entries) {
            val r = Response.decode(Response(-9, st, ByteArray(100) { it.toByte() }).encode())
            assertEquals(st, r.status); assertEquals(-9L, r.requestId); assertEquals(100, r.payload.size)
        }
    }

    @Test fun opCodesAreUniqueAndComplete() {
        assertEquals(Op.entries.size, Op.entries.map { it.code }.toSet().size)
        assertEquals(34, Op.entries.size)
        for (op in Op.entries) assertEquals(op, Op.fromCode(op.code))
        assertNull(Op.fromCode(0)); assertNull(Op.fromCode(9999))
    }

    @Test fun badVersionStatusAndOpcodeThrow() {
        val good = Request(1, Op.OPEN, ByteArray(0)).encode()
        val badVersion = good.copyOf().also { it[0] = 9 }
        assertFailsWith<WireException> { Request.decode(badVersion) }
        val badOp = WireWriter().u8(PROTOCOL_VERSION).i64(1).i32(9999).bytes(ByteArray(0)).toByteArray()
        assertFailsWith<WireException> { Request.decode(badOp) }
        val badStatus = WireWriter().u8(PROTOCOL_VERSION).i64(1).u8(7).bytes(ByteArray(0)).toByteArray()
        assertFailsWith<WireException> { Response.decode(badStatus) }
    }

    @Test fun payloadLengthPrefixHostile() {
        val f = WireWriter().u8(PROTOCOL_VERSION).i64(1).i32(Op.META.code).i32(0x7FFFFFFF).toByteArray()
        assertFailsWith<WireException> { Request.decode(f) }
    }

    @Test fun toStringHidesPayload() {
        assertFalse(Request(1, Op.OPEN, "secret".toByteArray()).toString().contains("secret"))
    }

    @Test fun heapTileBufferDuplicates() {
        val b = ByteBuffer.allocate(8); b.put(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)); b.flip()
        val t = HeapTileBuffer(5, b)
        assertEquals(8, t.size); assertEquals(5L, t.transferId)
        val a = t.asByteBuffer(); a.get()
        assertEquals(0, t.asByteBuffer().position())
    }
}

class ErrorCodecTest {
    private val all: List<EngineError> = listOf(
        EngineError.InvalidPdf("a"), EngineError.PasswordRequired, EngineError.WrongPassword, EngineError.TooLarge("b"),
        EngineError.InvalidArgument("c"), EngineError.Render("d"), EngineError.Panic("e"), EngineError.Unsupported("f"),
        EngineError.Io("g"), EngineError.Cancelled, EngineError.NotPermitted("h"), EngineError.Timeout("i"), EngineError.EngineDied("j"),
    )

    @Test fun everySubclassRoundTripsWithItsType() {
        for (e in all) {
            val back = ErrorCodec.decode(ErrorCodec.encode(e))
            assertEquals(e.javaClass, back.javaClass)
            if (e !is EngineError.PasswordRequired && e !is EngineError.WrongPassword && e !is EngineError.Cancelled) {
                assertEquals(e.detail, back.detail)
            }
        }
    }

    @Test fun singletonsStaySingletons() {
        assertTrue(ErrorCodec.decode(ErrorCodec.encode(EngineError.PasswordRequired)) === EngineError.PasswordRequired)
        assertTrue(ErrorCodec.decode(ErrorCodec.encode(EngineError.WrongPassword)) === EngineError.WrongPassword)
        assertTrue(ErrorCodec.decode(ErrorCodec.encode(EngineError.Cancelled)) === EngineError.Cancelled)
    }

    @Test fun codesAreDistinct() {
        assertEquals(all.size, all.map { ErrorCodec.codeOf(it) }.toSet().size)
    }

    @Test fun clientOnlyFlags() {
        assertTrue(ErrorCodec.isClientOnly(EngineError.Timeout("x")))
        assertTrue(ErrorCodec.isClientOnly(EngineError.EngineDied("x")))
        assertFalse(ErrorCodec.isClientOnly(EngineError.Panic("x")))
    }

    @Test fun unknownCodeThrows() {
        assertFailsWith<WireException> { ErrorCodec.decode(WireWriter().u8(200).string("x").toByteArray()) }
        assertFailsWith<WireException> { ErrorCodec.decode(WireWriter().u8(0).string("x").toByteArray()) }
    }

    @Test fun longDetailIsTruncated() {
        val back = ErrorCodec.decode(ErrorCodec.encode(EngineError.Io("x".repeat(100_000))))
        assertTrue(back.detail.length <= 16 * 1024)
    }
}
