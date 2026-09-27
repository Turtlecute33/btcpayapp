package com.btcpayapp.ui.screens.apps

import com.btcpayapp.core.util.Text
import com.btcpayapp.data.api.ApiJson
import com.btcpayapp.data.api.dto.AppItem
import com.btcpayapp.data.api.dto.AppItemPriceType
import com.btcpayapp.data.api.dto.CrowdfundAppData
import com.btcpayapp.data.api.dto.CrowdfundAppRequest
import com.btcpayapp.data.api.dto.PointOfSaleAppData
import com.btcpayapp.data.api.dto.PosView
import com.btcpayapp.ui.screens.paymentrequest.PaymentRequestEditState
import com.btcpayapp.ui.screens.paymentrequest.descriptionToSend
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App and payment-request updates replace the whole record on the server, so
 * a save must send back everything it loaded and change only what the user
 * edited. Before, one rename wiped every item's image and tax rate, the HTML
 * settings and a payment request's rich description and form.
 */
class RoundTripTest {

    private val json = ApiJson.instance

    private fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private val rawItem = obj(
        """{"id":"tea","title":"Tea","description":"Hot","image":"https://shop.example/tea.png",""" +
            """"price":"3.5","priceType":"Fixed","buyButtonText":"Buy tea","taxRate":8.5,""" +
            """"paymentMethods":["BTC-CHAIN"],"addedLater":{"x":1}}""",
    )

    @Test
    fun `mergeItem keeps what the editor does not show and applies the edits`() {
        val original = json.decodeFromJsonElement(AppItem.serializer(), rawItem)

        val merged = mergeItem(rawItem, original.copy(title = "Green tea", description = null))

        assertEquals(JsonPrimitive("Green tea"), merged["title"])
        assertEquals("a cleared description is sent as null", JsonNull, merged["description"])
        assertEquals(rawItem["image"], merged["image"])
        assertEquals(rawItem["buyButtonText"], merged["buyButtonText"])
        assertEquals("8.5", merged["taxRate"]?.jsonPrimitive?.content)
        assertEquals(rawItem["paymentMethods"], merged["paymentMethods"])
        assertEquals(rawItem["addedLater"], merged["addedLater"])
    }

    @Test
    fun `an item price type this app cannot read goes back as the server wrote it`() {
        val raw = obj("""{"id":"gift","title":"Gift","priceType":"Subscription"}""")
        val original = json.decodeFromJsonElement(AppItem.serializer(), raw)
        assertEquals(AppItemPriceType.Unknown, original.priceType)

        val merged = mergeItem(raw, original.copy(title = "Gift card"))

        assertEquals(JsonPrimitive("Subscription"), merged["priceType"])
        assertEquals(JsonPrimitive("Gift card"), merged["title"])
    }

    @Test
    fun `a new item has no raw json and is sent as edited`() {
        val merged = mergeItem(null, AppItem(id = "cake", title = "Cake", price = "4".toBigDecimal()))

        assertEquals(JsonPrimitive("cake"), merged["id"])
        assertEquals(JsonPrimitive("4"), merged["price"])
        assertEquals(JsonNull, merged["inventory"])
    }

    @Test
    fun `the pos body keeps unmodelled settings and drops the read-only keys`() {
        val raw = obj(
            """{"id":"app1","storeId":"s1","created":1,"appType":"PointOfSale","archived":false,""" +
                """"appName":"Shop","description":"Old","defaultView":"Kiosk","htmlLang":"de",""" +
                """"htmlMetaTags":"<meta name=\"robots\" content=\"noindex\">","formId":"f1",""" +
                """"items":[{"id":"tea"}],"addedLater":true}""",
        )
        val loaded = json.decodeFromJsonElement(PointOfSaleAppData.serializer(), raw)
        assertEquals(PosView.Unknown, loaded.defaultView)

        // What the screen sends: the form's fields, loaded then edited.
        val form = PointOfSaleEditState().withLoaded(loaded).copy(appName = "Shop 2", description = "")
        val body = pointOfSaleBody(raw, form.toRequest(template = "[]"))

        assertEquals(JsonPrimitive("Shop 2"), body["appName"])
        assertEquals("a view from a newer server survives", JsonPrimitive("Kiosk"), body["defaultView"])
        assertEquals(raw["htmlLang"], body["htmlLang"])
        assertEquals(raw["htmlMetaTags"], body["htmlMetaTags"])
        assertEquals(raw["formId"], body["formId"])
        assertEquals(raw["addedLater"], body["addedLater"])
        assertEquals("a field the form can empty is cleared", JsonNull, body["description"])
        assertEquals(JsonPrimitive("[]"), body["template"])
        for (key in listOf("items", "id", "storeId", "created", "appType", "archived")) {
            assertFalse(key, key in body)
        }
    }

    @Test
    fun `an unchanged point of sale goes back as it came`() {
        val raw = obj(
            """{"id":"app1","storeId":"s1","created":1,"appType":"PointOfSale","archived":false,""" +
                """"appName":"Shop","title":"Till","description":"Old","defaultView":"Cart","showItems":true,""" +
                """"showCustomAmount":false,"showDiscount":true,"showSearch":true,"showCategories":false,""" +
                """"enableTips":true,"currency":"EUR","fixedAmountPayButtonText":"Buy",""" +
                """"customAmountPayButtonText":"Pay","tipText":"Tip?","customTipPercentages":[10,20],""" +
                """"notificationUrl":"https://shop.example/n","redirectUrl":"https://shop.example/r",""" +
                """"redirectAutomatically":false,"htmlLang":"de","formId":"f1","items":[{"id":"tea"}],""" +
                """"addedLater":true}""",
        )
        val loaded = json.decodeFromJsonElement(PointOfSaleAppData.serializer(), raw)

        val body = pointOfSaleBody(raw, PointOfSaleEditState().withLoaded(loaded).toRequest(template = "[]"))

        val dropped = setOf("id", "storeId", "created", "appType", "archived", "items")
        assertEquals(JsonObject(raw - dropped + ("template" to JsonPrimitive("[]"))), body)
    }

    @Test
    fun `an unchanged crowdfund goes back as it came`() {
        val raw = obj(
            """{"id":"cf1","storeId":"s1","created":1,"appType":"Crowdfund","archived":false,""" +
                """"appName":"Roof","title":"New roof","description":"Help","tagline":"Dry at last",""" +
                """"enabled":true,"enforceTargetAmount":false,"startDate":1690000000,"endDate":1700000000,""" +
                """"targetCurrency":"EUR","targetAmount":"1000","mainImageUrl":"https://shop.example/roof.png",""" +
                """"notificationUrl":"https://shop.example/n","soundsEnabled":false,"animationsEnabled":true,""" +
                """"resetEveryAmount":1,"resetEvery":"Day","displayPerksValue":true,"displayPerksRanking":false,""" +
                """"sortPerksByPopularity":true,"sounds":["a.mp3"],"animationColors":["#fff"],"htmlLang":"fr",""" +
                """"formId":"shipping","perks":[{"id":"p1"}]}""",
        )
        val loaded = json.decodeFromJsonElement(CrowdfundAppData.serializer(), raw)

        val body = crowdfundBody(raw, CrowdfundEditState().withLoaded(loaded).toRequest())

        val dropped = setOf("id", "storeId", "created", "appType", "archived", "perks")
        val perks = JsonPrimitive("""[{"id":"p1"}]""")
        assertEquals(JsonObject(raw - dropped + ("perksTemplate" to perks)), body)
    }

    @Test
    fun `the crowdfund body keeps the form, sounds and colours and drops the perks array`() {
        val raw = obj(
            """{"id":"cf1","appName":"Roof","formId":"shipping","sounds":["a.mp3"],""" +
                """"animationColors":["#fff"],"htmlLang":"fr","perks":[{"id":"p1"}],"endDate":1700000000}""",
        )
        val request = CrowdfundAppRequest(appName = "New roof", perksTemplate = """[{"id":"p1"}]""")

        val body = crowdfundBody(raw, request)

        assertEquals(JsonPrimitive("New roof"), body["appName"])
        for (key in listOf("formId", "sounds", "animationColors", "htmlLang")) {
            assertEquals(key, raw[key], body[key])
        }
        assertEquals("a cleared end date is sent as null", JsonNull, body["endDate"])
        assertEquals(JsonPrimitive("""[{"id":"p1"}]"""), body["perksTemplate"])
        assertFalse("perks" in body)
    }

    @Test
    fun `descriptionToSend returns the original html until the text is edited`() {
        val html = "<p>Pay for <b>order 42</b></p><p>Thanks</p>"
        val shown = Text.stripHtml(html)

        assertEquals(html, descriptionToSend(html, shown))
        assertEquals("whitespace around the text is not an edit", html, descriptionToSend(html, "  $shown\n"))
        assertEquals("New text", descriptionToSend(html, "New text"))
        assertNull("a cleared description is cleared", descriptionToSend(html, ""))
        assertNull(descriptionToSend(null, ""))
    }

    @Test
    fun `only a description with markup warns that an edit drops it`() {
        assertTrue(PaymentRequestEditState(originalHtml = "<p>Pay for <b>order 42</b></p>").formatted)
        assertFalse(PaymentRequestEditState(originalHtml = " Pay for order 42 ").formatted)
        assertFalse(PaymentRequestEditState().formatted)
    }

    @Test
    fun `an item price must parse and fixed or minimum items need one`() {
        assertEquals("Enter a number, for example 12.50.", itemPriceProblem("10 €", AppItemPriceType.Fixed))
        assertEquals("Enter a price, or 0 for a free item.", itemPriceProblem("", AppItemPriceType.Fixed))
        assertEquals("Enter a minimum price above zero.", itemPriceProblem("0", AppItemPriceType.Minimum))
        assertEquals("A price cannot be below zero.", itemPriceProblem("-1", AppItemPriceType.Topup))
        assertNull(itemPriceProblem("0", AppItemPriceType.Fixed))
        assertNull(itemPriceProblem("12,50", AppItemPriceType.Fixed))
        assertNull(itemPriceProblem("", AppItemPriceType.Topup))
    }
}
