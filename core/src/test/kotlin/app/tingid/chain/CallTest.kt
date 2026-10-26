// Ting Call Requests (TCR-1.md): templates, values, hashes and call links,
// checked against the worked example in the spec.
package app.tingid.chain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class CallTest {
    @Test
    fun `reads a program's events, and nothing else as one`() {
        val account = filled(1)
        val executed = Tcr.EXECUTED_TOPIC + account + u64le(4) + filled(9)
        assertEquals(CallEvent.Executed(account, 4, filled(9)), Tcr.decodeEvent(executed))
        assertNull(Tcr.decodeEvent(executed + byteArrayOf(0)))
        assertNull(Tcr.decodeEvent(executed.copyOf(executed.size - 1)))
        assertNull(Tcr.decodeEvent(Coin.EXECUTED_TOPIC + account + u64le(4)))
        // A scheduled event's values run to its end, and may be none.
        assertEquals(CallEvent.Scheduled(account, 1, 99, filled(7), ByteArray(0)), Tcr.decodeEvent(Tcr.SCHEDULED_TOPIC + account + u64le(1) + u64le(99) + filled(7)))
        assertEquals(CallEvent.Cancelled(account, 2), Tcr.decodeEvent(Tcr.CANCELLED_TOPIC + account + u64le(2)))
    }

    private val chain = unhex("15d6549cfacd816f835c632b4c67308d1c63e642f4cbf4049368430670705107")
    private val program = "765a9fa0c02d1ff7bbb1bf47ece78424c3d52627811999c874afa8895b4f7fd5"
    private val daler = "305378d17f6af84a563d0f8a8455084948aff7330b62b4f2f98a44b82ba113c4"
    private val give = "give(item:u64,to:account): Give item {item} to {to}"
    private val buy = "buy(item:u64): Buy item {item} for {payment}"
    private val giveLink = "ting:call?net=15d6549c&program=$program" +
        "&template=give(item%3Au64%2Cto%3Aaccount)%3A%20Give%20item%20%7Bitem%7D%20to%20%7Bto%7D" +
        "&values=BwAAAAAAAAAREREREREREREREREREREREREREREREREREREREREREQ"
    private val buyLink = "ting:call?net=15d6549c&program=$program" +
        "&template=buy(item%3Au64)%3A%20Buy%20item%20%7Bitem%7D%20for%20%7Bpayment%7D&values=BwAAAAAAAAA"

    private fun refuses(text: String, why: String) {
        val e = assertThrows(LinkError::class.java) { Links.parse(text) }
        assertTrue("${e.message} should say $why", e.message!!.contains(why))
    }

    @Test
    fun `the spec's example hashes and message`() {
        val values = unhex("0700000000000000" + "11".repeat(32))
        val t = Tcr.templateHash(give)
        assertEquals("136b3686867293c0763efe081477ede6d5b6f4d4c92cbbf3744b9a1932b9b3e8", t.hex())
        val operation = Tcr.operationHash(t, values)
        assertEquals("b0ea3fb7ceacdd8246d27aba4b87381b1273b90fe58bb4d917d235ae9d805084", operation.hex())
        val message = Tcr.message(chain, unhex(program), filled(0x22), 0, 1793000000, operation)
        assertEquals("2af209f9f7928bac0254bcf9e40c60d0f74fdf6f77de71df567c1a0db6450ee3", signingDigest(message).hex())
        val bought = Tcr.operationHash(Tcr.templateHash(buy), u64le(7))
        assertEquals("f7b7c40915e60615bd017682423d2b9b680faf3dc52d1748f8969cff8c22b2fe", Tcr.templateHash(buy).hex())
        assertEquals("806780c812e173d3c30d947e04c8a454a569d6998e1b5a19e92a3f36e95cead3", bought.hex())
        assertEquals(
            "65c295a6077b636553d933b06a3c21042cf9b366db00ac6680a924d388bca8e5",
            signingDigest(Tcr.message(chain, unhex(program), filled(0x22), 1, 1793000000, bought)).hex(),
        )
        assertEquals("5e5d1a485b7c790dcadf931959467bda642e5f8c3eeee945a96118174c033979", Tcr.TEMPLATES_KEY.hex())
        assertEquals("74a2fd2e9de861c7950a320ae9c7c93ad113aac470fce95024686743a3a2e77f", Tcr.nonceKey(filled(0x22)).hex())
        assertEquals("5d42b569802462ea7155e42210db5ac79ef8f3f8d5963f986ac4b8f206b266a2", Tcr.scheduledKey(filled(0x22), 0).hex())
        assertEquals("8381accdf4b5f2fda7f3211ce6165cf75c5d2c1c83d9363a6eff54cba06f9ebf", Tcr.RUN.hex())
        assertEquals("87b120eab58d69a8ae3ace1fbac076394efc796c00464fcd19347c06291d2ec4", Tcr.SCHEDULED_TOPIC.hex())
    }

    @Test
    fun `templates declare their fields and show them in any order`() {
        val t = Template.parse("paint(week:u64,pixels:list(x:u16,y:u16,color:enum(red|light blue|dark green))): On week {week}, paint {pixels} pixels, burning {payment}")
        assertEquals("paint", t.operation)
        assertEquals(listOf("week", "pixels"), t.fields.map { it.name })
        val list = t.fields[1].type as SlotType.ListOf
        assertEquals(listOf("x", "y", "color"), list.fields.map { it.name })
        assertEquals(listOf("red", "light blue", "dark green"), (list.fields[2].type as SlotType.Enum).labels)
        assertTrue(t.showsPayment)
        // German puts the account first; the declaration, and so the bytes, stay the same.
        val de = Template.parse("give(item:u64,to:account): Gib {to} den Gegenstand {item}")
        assertEquals(Template.parse(give).declaration, de.declaration)
        assertEquals(1, (de.parts[1] as Template.Part.Field).index)
        assertEquals(2, Template.parse("give(item:u64,to:account): Give {item} to {to}, only {item}").parts.count { it is Template.Part.Field && it.index == 0 })
        assertEquals(0, Template.parse("unlist(): Take the item off sale").fields.size)
        assertFalse(Template.parse(give).showsPayment)
    }

    @Test
    fun `templates that break the rules are refused`() {
        fun bad(text: String, why: String) {
            val e = assertThrows(CallError::class.java) { Template.parse(text) }
            assertTrue("${e.message} should say $why", e.message!!.contains(why))
        }
        bad("Give {item:u64}", "declares no operation")
        bad("Give(item:u64): x {item}", "not a name")
        bad("give(item:u64) Give {item}", "followed by")
        bad("give(item:u64,item:account): Give {item}", "twice")
        bad("give(payment:u64): Give {payment}", "names the payment")
        bad("give(item:u65): Give {item}", "does not know")
        bad("give(item): Give {item}", "without a type")
        bad("give(item:u64,to:account): Give item {item}", "does not show its field to")
        bad("give(item:u64): Give {item} to {to}", "does not declare")
        bad("give(item:u64): Give } {item}", "outside a slot")
        bad("give(item:u64): Give {item", "without its }")
        bad("give(item:u64): ", "surrounding spaces")
        bad("paint(p:list(a:u8,b:list(c:u8))): Paint {p}", "does not know")
        bad("paint(p:list(a:u8,a:u16)): Paint {p}", "twice")
        bad("paint(p:list()): Paint {p}", "without fields")
        bad("pick(c:enum(a,b)): Pick {c}", "not a label")
        bad("pick(c:enum(red|red)): Pick {c}", "label twice")
        bad("pick(c:enum(red||blue)): Pick {c}", "not a label")
        bad("pick(c:enum(red| blue)): Pick {c}", "not a label")
        bad("give(item:u64): Give‮{item}", "cannot be shown")
        bad("give(item:u64): " + "a".repeat(500) + "{item}", "longer than 512")
    }

    @Test
    fun `values decode exactly, in declared order`() {
        val t = Template.parse("paint(pixels:list(pixel:u32,color:enum(red|blue)),title:text,price:amount): Paint {pixels} named {title} for {price}")
        val bytes = Writer().u64(2).u32(1000).u8(0).u32(1001).u8(1).bytes(utf8("Dusk")).put(unhex(daler)).u128(BigInteger.valueOf(25)).finish()
        val v = decodeValues(t, bytes)
        val pixels = v[0] as SlotValue.ListOf
        assertEquals(2, pixels.items.size)
        assertEquals("blue", (pixels.items[1][1] as SlotValue.Enum).label)
        assertEquals("Dusk", (v[1] as SlotValue.Text).text)
        assertEquals(daler, (v[2] as SlotValue.Amount).coin.hex())
        assertThrows(CallError::class.java) { decodeValues(t, bytes + byteArrayOf(0)) }
        assertThrows(CallError::class.java) { decodeValues(t, bytes.copyOf(bytes.size - 1)) }
        // An enum index past the last label is refused.
        val past = Writer().u64(1).u32(1).u8(2).bytes(utf8("Dusk")).put(unhex(daler)).u128(BigInteger.ONE).finish()
        assertThrows(CallError::class.java) { decodeValues(t, past) }
        // A count that cannot fit in what is left is refused, not looped over.
        assertThrows(CallError::class.java) { decodeValues(t, Writer().u64(1L shl 40).finish()) }
        val spaced = Writer().u64(0).bytes(utf8(" Dusk")).put(unhex(daler)).u128(BigInteger.ONE).finish()
        assertThrows(CallError::class.java) { decodeValues(t, spaced) }
    }

    @Test
    fun `the spec's give link reads`() {
        val link = Links.parse(giveLink) as Link.Call
        assertEquals(program, link.program)
        assertEquals(give, link.template.text)
        assertEquals(BigInteger.valueOf(7), (link.decoded[0] as SlotValue.Integer).value)
        assertFalse(link.pays)
        // Written again, it reads the same.
        val again = Links.parse(Links.encode(link)) as Link.Call
        assertArrayEquals(link.values, again.values)
        assertEquals(link.template.text, again.template.text)
    }

    @Test
    fun `compressed values unpack to the same`() {
        val packed = giveLink.substringBefore("&values=") + "&zvalues=Y2eAAEECAAA"
        val link = Links.parse(packed) as Link.Call
        assertEquals("0700000000000000" + "11".repeat(32), link.values.hex())
        refuses(packed + "&values=BwAAAAAAAAAREREREREREREREREREREREREREREREREREREREREREQ", "twice")
        refuses(giveLink.substringBefore("&values=") + "&zvalues=Y2eAAEECAAAA", "after their end")
        refuses(giveLink.substringBefore("&values=") + "&zvalues=Y2eAAA", "end too soon")
    }

    @Test
    fun `payment fields go together, and a sentence that shows the payment needs one`() {
        val paid = Links.parse("$buyLink&coin=$daler&to=${"33".repeat(32)}&amount=25") as Link.Call
        assertTrue(paid.pays)
        assertEquals("25", paid.amount)
        refuses(buyLink, "shows a payment")
        refuses("$buyLink&to=${"33".repeat(32)}&amount=25", "without its coin")
        refuses("$buyLink&coin=$daler&burn=1&to=${"33".repeat(32)}&amount=25", "both burns and pays")
        refuses("$buyLink&coin=$daler&to=${"33".repeat(32)}", "no amount")
        refuses("$buyLink&coin=$daler&burn=0.000", "is 0")
        refuses("$giveLink&coin=$daler&burn=1&delayed=1", "delay")
        refuses("$giveLink&coin=$daler&burn=1", "not in its template")
        refuses("$giveLink&burn=1", "without its coin")
        // `coin` alone is no payment: it names the coin of a fee payment, and goes with `delayed`.
        val fee = Links.parse("$giveLink&coin=$daler&from=${"22".repeat(32)}&delayed=1") as Link.Call
        assertFalse(fee.pays)
        assertEquals(daler, fee.coin)
        assertTrue(fee.delayed)
        refuses("$giveLink&delayed=yes", "not 1")
        refuses(giveLink.replace("&values=", "&values=AA"), "values")
        refuses(giveLink.replace("u64%2Cto", "u65%2Cto"), "does not know")
    }

    @Test
    fun `a return address is a secure one without a fragment`() {
        val link = Links.parse("$giveLink&return=${Links.encodeComponent("https://canvas.example/week/1")}") as Link.Call
        assertEquals("https://canvas.example/week/1", link.returnTo)
        assertEquals("http://localhost:8080/", (Links.parse("$giveLink&return=http%3A%2F%2Flocalhost%3A8080%2F") as Link.Call).returnTo)
        refuses("$giveLink&return=http%3A%2F%2Fcanvas.example%2F", "return address")
        refuses("$giveLink&return=https%3A%2F%2Fcanvas.example%2F%23x", "return address")
        refuses("$giveLink&return=javascript%3Aalert(1)", "return address")
    }

    @Test
    fun `the call carries the approval and the payment`() {
        val t = Tcr.templateHash(give)
        val values = unhex("0700000000000000" + "11".repeat(32))
        val auth = Tcr.Auth(filled(0xaa), byteArrayOf(1, 2), 5, 1793000000, byteArrayOf(9, 9))
        val alone = Tcr.callInput(t, values, auth, null)
        val r = Reader(alone)
        assertArrayEquals(t, r.array32())
        assertArrayEquals(values, r.take(values.size))
        assertEquals(0, r.u8())
        assertArrayEquals(filled(0xaa), r.array32())
        assertArrayEquals(byteArrayOf(1, 2), r.bytes())
        assertEquals(5L, r.u64())
        assertEquals(1793000000L, r.u64())
        assertArrayEquals(byteArrayOf(9, 9), r.bytes())
        // No payments: a count of 0.
        assertEquals(0, r.u8())
        r.end()
        // One payment: a count of 1, the coin and its input.
        val paid = Tcr.callInput(t, values, auth, Tcr.Payment(unhex(daler), byteArrayOf(0x10, 0)))
        assertEquals(alone.size + 32 + 4 + 2, paid.size)
        assertEquals(1, paid[alone.size - 1].toInt())
    }

    @Test
    fun `the template list and nonce records`() {
        val list = Writer().u64(2).bytes(utf8(give)).bytes(utf8(buy)).finish()
        assertEquals(listOf(give, buy), Tcr.decodeTemplateList(list))
        assertNull(Tcr.listProblem(listOf(give, buy), Template.parse(give)))
        assertTrue(Tcr.listProblem(listOf(give, give), Template.parse(give))!!.contains("twice"))
        assertTrue(Tcr.listProblem(listOf(buy), Template.parse(give))!!.contains("no operation"))
        val other = "give(item:u64,to:account,note:text): Give item {item} to {to}: {note}"
        assertTrue(Tcr.listProblem(listOf(give, other), Template.parse(give))!!.contains("two ways"))
        assertNull(Tcr.decodeTemplateList(list + byteArrayOf(0)))
        assertNull(Tcr.decodeTemplateList(Writer().u64(1L shl 40).finish()))
        assertEquals(0L, Tcr.decodeNonce(null))
        assertEquals(3L, Tcr.decodeNonce(u64le(3)))
        assertNull(Tcr.decodeNonce(byteArrayOf(1)))
    }
}
