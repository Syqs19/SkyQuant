package dev.syqs.skyquant.feature.bazaar.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The profit maths behind the Craft and Forge pages.
 *
 * Every rule here inverts silently when it is got wrong: prices come from the two sides of an
 * order book, and using the wrong side produces a plausible number rather than an error. The
 * cases below are the ones where a mistake would be invisible on screen.
 */
class CraftProfitTest {

    /**
     * [instantBuy] is what buying outright costs, and defaults to [topAsk] so the two are equal
     * unless a test needs them apart - which is the ordinary case anyway: measured across 622
     * liquid products the median gap between them is 0%.
     */
    private fun quote(
        id: String,
        topAsk: Double = 0.0,
        sellPrice: Double = 0.0,
        topBid: Double = 0.0,
        weekly: Long = 1_000_000,
        instantBuy: Double = topAsk,
    ) = BazaarLivePrices.Quote(
        productId = id,
        buyPrice = instantBuy,
        sellPrice = sellPrice,
        buyVolume = weekly,
        sellVolume = weekly,
        buyMovingWeek = weekly,
        sellMovingWeek = weekly,
        topBid = topBid,
        topAsk = topAsk,
    )

    private fun price(
        recipe: Recipe,
        quotes: Map<String, BazaarLivePrices.Quote>,
        tax: Double = 0.0,
    ) = CraftProfit.price(recipe, quoteFor = { quotes[it] }, taxRate = { tax })

    @Test
    fun `cost is the ingredient's asking price times how many are needed`() {
        // ENCHANTED_DIAMOND is 160 diamonds. Reading the amount wrong is the difference between
        // a profitable row and a ruinous one, and nothing on screen would show which.
        val recipe = Recipe("ENCHANTED_DIAMOND", 1.0, mapOf("DIAMOND" to 160.0))
        val quotes = mapOf(
            "DIAMOND" to quote("DIAMOND", topAsk = 10.0),
            "ENCHANTED_DIAMOND" to quote("ENCHANTED_DIAMOND", sellPrice = 2000.0, topBid = 2000.0),
        )

        val craft = price(recipe, quotes)!!

        assertEquals(1600.0, craft.cost, 1e-9)
        assertEquals(400.0, craft.instantProfit, 1e-9)
    }

    @Test
    fun `the output count multiplies the revenue`() {
        // DIAMOND's recipe yields 9 from one block. Pricing it as one would report a loss on a
        // recipe that makes money.
        val recipe = Recipe("DIAMOND", 9.0, mapOf("DIAMOND_BLOCK" to 1.0))
        val quotes = mapOf(
            "DIAMOND_BLOCK" to quote("DIAMOND_BLOCK", topAsk = 72.0),
            "DIAMOND" to quote("DIAMOND", sellPrice = 10.0, topBid = 10.0),
        )

        val craft = price(recipe, quotes)!!

        assertEquals(72.0, craft.cost, 1e-9)
        assertEquals(90.0 - 72.0, craft.instantProfit, 1e-9)
    }

    @Test
    fun `the sale is taxed and the purchase is not`() {
        // A bazaar sale pays the cut; buying the ingredients doesn't. Taxing both, or neither,
        // shifts every row on the page by the same wrong amount - which looks consistent.
        val recipe = Recipe("OUT", 1.0, mapOf("IN" to 1.0))
        val quotes = mapOf(
            "IN" to quote("IN", topAsk = 100.0),
            "OUT" to quote("OUT", sellPrice = 200.0, topBid = 200.0),
        )

        val craft = price(recipe, quotes, tax = 0.0125)!!

        assertEquals(100.0, craft.cost, 1e-9)
        assertEquals(200.0 * 0.9875 - 100.0, craft.instantProfit, 1e-9)
    }

    @Test
    fun `instant and order are different exits`() {
        // sellPrice is what buyers are bidding right now; topBid is where a sell offer fills.
        // Collapsing them into one figure either hides the opportunity or overstates its
        // certainty - the same reason NpcFlipSummary shows both.
        val recipe = Recipe("OUT", 1.0, mapOf("IN" to 1.0))
        val quotes = mapOf(
            "IN" to quote("IN", topAsk = 100.0),
            "OUT" to quote("OUT", sellPrice = 150.0, topBid = 220.0),
        )

        val craft = price(recipe, quotes)!!

        assertEquals(50.0, craft.instantProfit, 1e-9)
        assertEquals(120.0, craft.orderProfit, 1e-9)
        assertTrue(craft.orderProfit > craft.instantProfit)
    }

    @Test
    fun `cost uses the standing ask, never the summary price`() {
        // The trap this guards: `quick_status` can quote a price nobody is offering. SHARD_DRYBARK
        // summarised at 22.7 while the cheapest actual seller wanted 7002 - pricing off the
        // summary made it look like the best trade on the bazaar by a factor of ten.
        val recipe = Recipe("OUT", 1.0, mapOf("IN" to 1.0))
        val quotes = mapOf(
            // buyPrice deliberately far from topAsk, as the real bug had it.
            "IN" to BazaarLivePrices.Quote(
                productId = "IN",
                buyPrice = 22.7,
                sellPrice = 20.0,
                buyVolume = 100,
                sellVolume = 100,
                buyMovingWeek = 100,
                sellMovingWeek = 100,
                topBid = 20.0,
                topAsk = 7002.0,
            ),
            "OUT" to quote("OUT", sellPrice = 8000.0, topBid = 8000.0),
        )

        val craft = price(recipe, quotes)!!

        assertEquals(7002.0, craft.cost, 1e-9)
    }

    @Test
    fun `each profit pairs the cost its own trade actually pays`() {
        // The bug this replaces: both profits divided the same order cost, so the "instant"
        // figure was fast on the way out and patient on the way in - a trade nobody can make,
        // and one that flattered 8 of the 29 rows on the Craft page into showing a gain where
        // buying the fast way loses money.
        val recipe = Recipe("OUT", 1.0, mapOf("IN" to 1.0))
        val quotes = mapOf(
            // Bought outright it costs 200; on an order, 100. The real gap for cheap materials.
            "IN" to BazaarLivePrices.Quote(
                productId = "IN",
                buyPrice = 200.0,
                sellPrice = 90.0,
                buyVolume = 100_000,
                sellVolume = 100_000,
                buyMovingWeek = 100_000,
                sellMovingWeek = 100_000,
                topBid = 90.0,
                topAsk = 100.0,
            ),
            "OUT" to quote("OUT", sellPrice = 150.0, topBid = 220.0),
        )

        val craft = price(recipe, quotes)!!

        assertEquals(100.0, craft.cost, 1e-9)
        assertEquals(200.0, craft.instantCost, 1e-9)
        // Fast: pay 200, receive 150 -> a loss. Patient: pay 100, receive 220 -> a gain.
        assertEquals(-50.0, craft.instantProfit, 1e-9)
        assertEquals(120.0, craft.orderProfit, 1e-9)
        assertTrue(craft.instantProfit < 0 && craft.orderProfit > 0, "the two trades must differ in sign here")
    }

    @Test
    fun `each margin divides by its own trade's outlay`() {
        // Dividing both by the order cost would understate how much worse the fast trade is: it
        // puts up more money for less return, and only one half of that shows in the profit.
        val recipe = Recipe("OUT", 1.0, mapOf("IN" to 1.0))
        val quotes = mapOf(
            "IN" to BazaarLivePrices.Quote(
                productId = "IN",
                buyPrice = 200.0,
                sellPrice = 90.0,
                buyVolume = 100_000,
                sellVolume = 100_000,
                buyMovingWeek = 100_000,
                sellMovingWeek = 100_000,
                topBid = 90.0,
                topAsk = 100.0,
            ),
            "OUT" to quote("OUT", sellPrice = 300.0, topBid = 300.0),
        )

        val craft = price(recipe, quotes)!!

        // Fast: 100 profit on 200 put up. Patient: 200 on 100.
        assertEquals(50.0, craft.instantMargin, 1e-9)
        assertEquals(200.0, craft.orderMargin, 1e-9)
    }

    @Test
    fun `an ingredient with no instant price falls back to its ask`() {
        // A missing summary must not read as a free ingredient, which would make the fast trade
        // look like the better one on exactly the items where it isn't.
        val recipe = Recipe("OUT", 1.0, mapOf("IN" to 2.0))
        val quotes = mapOf(
            "IN" to quote("IN", topAsk = 50.0), // buyPrice defaults to topAsk in this helper
            "OUT" to quote("OUT", sellPrice = 500.0, topBid = 500.0),
        )

        val craft = price(recipe, quotes)!!

        assertEquals(craft.cost, craft.instantCost, 1e-9)
        assertTrue(craft.instantCost > 0)
    }

    @Test
    fun `an output the bazaar doesn't trade is priced at auction`() {
        // What roughly doubles both pages: 502 crafting and 33 forge recipes have ingredients the
        // bazaar prices and an output it doesn't - weapons, armour, talismans, forge plates.
        // Before this they were dropped without trace.
        val recipe = Recipe("HYPERION", 1.0, mapOf("IN" to 1.0))
        val quotes = mapOf("IN" to quote("IN", topAsk = 100_000.0))

        val craft = CraftProfit.price(
            recipe,
            quoteFor = { quotes[it] },
            taxRate = { 0.0125 },
            auctionFor = { AuctionSellPrice.Quote(it, price = 1_000_000.0, lowest = 990_000.0, listingCount = 8) },
        )!!

        assertTrue(craft.fromAuction)
        // Exactly 1M: the 1% listing fee applies, but the claim fee does not - it starts *above*
        // a million, because it is capped so it can never take a sale below one.
        assertEquals(1_000_000.0 * (1 - 0.01) - 100_000.0, craft.orderProfit, 1.0)
    }

    @Test
    fun `the auction's tiered cut is used, not the bazaar's flat one`() {
        // A BIN costs 1% under 10M, 2% to 100M, 2.5% above, plus 1% to claim over a million.
        // Using the bazaar's 1.25% would understate the cut by more than double on exactly the
        // expensive items this path exists to price.
        assertEquals(0.01, AuctionSellPrice.taxFor(500_000.0), 1e-9)
        assertEquals(0.02, AuctionSellPrice.taxFor(5_000_000.0), 1e-9)
        assertEquals(0.03, AuctionSellPrice.taxFor(50_000_000.0), 1e-9)
        assertEquals(0.035, AuctionSellPrice.taxFor(200_000_000.0), 1e-9)
    }

    @Test
    fun `the claim fee starts above a million, not at it`() {
        // Caught by this suite while writing it: the fee is capped so it can never take a sale
        // below a million, so exactly 1M pays the listing fee alone. An off-by-one here is worth
        // 1% of every row sitting near the boundary.
        assertEquals(0.01, AuctionSellPrice.taxFor(1_000_000.0), 1e-9)
        assertEquals(0.02, AuctionSellPrice.taxFor(1_000_001.0), 1e-9)
    }

    @Test
    fun `an auction row has one sale price, not a fast and a patient one`() {
        // An auction has no order book: listing is the only way to sell. The fast/patient pair on
        // the page then describes how the *ingredients* were bought, which is a real distinction
        // and the one that actually varies.
        val recipe = Recipe("TALISMAN", 1.0, mapOf("IN" to 1.0))
        val quotes = mapOf(
            "IN" to BazaarLivePrices.Quote(
                productId = "IN",
                buyPrice = 300.0,
                sellPrice = 90.0,
                buyVolume = 100_000,
                sellVolume = 100_000,
                buyMovingWeek = 100_000,
                sellMovingWeek = 100_000,
                topBid = 90.0,
                topAsk = 100.0,
            ),
        )

        val craft = CraftProfit.price(
            recipe,
            quoteFor = { quotes[it] },
            taxRate = { 0.0125 },
            auctionFor = { AuctionSellPrice.Quote(it, price = 500_000.0, lowest = 495_000.0, listingCount = 6) },
        )!!

        // Same revenue both ways; only the ingredient cost differs, by exactly 300 - 100.
        assertEquals(200.0, craft.orderProfit - craft.instantProfit, 1e-9)
    }

    @Test
    fun `an output nobody lists at all stays unpriceable`() {
        // Falling back to zero would make every recipe producing it a total loss, which sorts to
        // the bottom and looks like real data rather than like a missing figure.
        val recipe = Recipe("UNSOLD", 1.0, mapOf("IN" to 1.0))

        assertNull(
            CraftProfit.price(
                recipe,
                quoteFor = { if (it == "IN") quote("IN", topAsk = 10.0) else null },
                taxRate = { 0.0 },
                auctionFor = { null },
            ),
        )
    }

    @Test
    fun `a thin auction market is flagged on the row`() {
        // Carried through from AuctionSellPrice so the page can mark it: the price is still the
        // median of four, but one of those four being a mistake is visible in it.
        val craft = CraftProfit.price(
            Recipe("DIVAN_DRILL", 1.0, mapOf("IN" to 1.0)),
            quoteFor = { if (it == "IN") quote("IN", topAsk = 10.0) else null },
            taxRate = { 0.0 },
            auctionFor = {
                AuctionSellPrice.Quote(it, price = 1_339_500_000.0, lowest = 420_250_000.0, listingCount = 12)
            },
        )!!

        assertTrue(craft.hasOutlierListing)
    }

    @Test
    fun `an unpriceable ingredient makes the whole recipe unpriceable`() {
        // Rather than skipping it and reporting a profit too high by exactly the missing cost.
        val recipe = Recipe("OUT", 1.0, mapOf("IN" to 1.0, "NOT_TRADED" to 1.0))
        val quotes = mapOf(
            "IN" to quote("IN", topAsk = 10.0),
            "OUT" to quote("OUT", sellPrice = 100.0, topBid = 100.0),
        )

        assertNull(price(recipe, quotes))
    }

    @Test
    fun `an output nobody trades is unpriceable too`() {
        val recipe = Recipe("OUT", 1.0, mapOf("IN" to 1.0))

        assertNull(price(recipe, mapOf("IN" to quote("IN", topAsk = 10.0))))
    }

    @Test
    fun `volume is the scarcest ingredient, not the output`() {
        // What caps how often the trade can actually be run. Taking the output's volume would
        // flatter a recipe whose result sells briskly but whose inputs nobody stocks.
        val recipe = Recipe("OUT", 1.0, mapOf("COMMON" to 1.0, "RARE" to 1.0))
        val quotes = mapOf(
            "COMMON" to quote("COMMON", topAsk = 1.0, weekly = 5_000_000),
            "RARE" to quote("RARE", topAsk = 1.0, weekly = 400),
            "OUT" to quote("OUT", sellPrice = 100.0, topBid = 100.0, weekly = 9_000_000),
        )

        assertEquals(400, price(recipe, quotes)!!.weeklyVolume)
    }

    @Test
    fun `a forge recipe is ranked per hour, not per craft`() {
        // The ordering is nothing like ranking on profit. Measured in the plan: Tungsten Key
        // makes 259k in 30 seconds (31.1M/h) while Gleaming Crystal makes 11.65M in 6 hours
        // (1.94M/h) - the bigger profit is the worse trade by a factor of sixteen.
        val key = Recipe("TUNGSTEN_KEY", 1.0, mapOf("IN" to 1.0), durationSeconds = 30)
        val crystal = Recipe("GLEAMING_CRYSTAL", 1.0, mapOf("IN" to 1.0), durationSeconds = 6 * 3600)

        val keyProfit = 259_000.0
        val crystalProfit = 11_650_000.0

        val keyCraft = Recipe("K", 1.0, mapOf("X" to 1.0), durationSeconds = key.durationSeconds)
        val crystalCraft = Recipe("C", 1.0, mapOf("X" to 1.0), durationSeconds = crystal.durationSeconds)

        val pricedKey = price(
            keyCraft,
            mapOf(
                "X" to quote("X", topAsk = 1.0),
                "K" to quote("K", sellPrice = keyProfit + 1.0, topBid = keyProfit + 1.0),
            ),
        )!!
        val pricedCrystal = price(
            crystalCraft,
            mapOf(
                "X" to quote("X", topAsk = 1.0),
                "C" to quote("C", sellPrice = crystalProfit + 1.0, topBid = crystalProfit + 1.0),
            ),
        )!!

        assertTrue(pricedCrystal.instantProfit > pricedKey.instantProfit, "the setup is the wrong way round")
        assertTrue(
            pricedKey.profitPerHour(pricedKey.instantProfit) >
                pricedCrystal.profitPerHour(pricedCrystal.instantProfit),
            "ranking by profit per hour must reverse the ranking by profit",
        )
    }

    @Test
    fun `an instant craft has no per-hour rate to divide by`() {
        // Zero duration must not divide by zero: that sorts as infinity and puts every crafting
        // recipe above every forge one.
        val craft = price(
            Recipe("OUT", 1.0, mapOf("IN" to 1.0)),
            mapOf(
                "IN" to quote("IN", topAsk = 10.0),
                "OUT" to quote("OUT", sellPrice = 100.0, topBid = 100.0),
            ),
        )!!

        assertEquals(90.0, craft.profitPerHour(craft.instantProfit), 1e-9)
        assertTrue(craft.profitPerHour(craft.instantProfit).isFinite())
    }

    @Test
    fun `thirty seconds is a real duration, not a rounding error`() {
        // 22 of the 120 forge recipes take 30 seconds. Rounding that to zero hours would divide
        // by zero; rounding it to "0h" on screen would read as instant.
        val craft = price(
            Recipe("OUT", 1.0, mapOf("IN" to 1.0), durationSeconds = 30),
            mapOf(
                "IN" to quote("IN", topAsk = 10.0),
                "OUT" to quote("OUT", sellPrice = 110.0, topBid = 110.0),
            ),
        )!!

        // 100 profit in 30 seconds is 12,000 an hour.
        assertEquals(12_000.0, craft.profitPerHour(craft.instantProfit), 1e-6)
    }

    @Test
    fun `a loss is reported as a loss rather than hidden`() {
        // Most recipes lose money; the page has to be able to say so, and the margin has to stay
        // negative rather than flipping sign somewhere in the arithmetic.
        val craft = price(
            Recipe("OUT", 1.0, mapOf("IN" to 1.0)),
            mapOf(
                "IN" to quote("IN", topAsk = 500.0),
                "OUT" to quote("OUT", sellPrice = 100.0, topBid = 100.0),
            ),
        )!!

        assertTrue(craft.instantProfit < 0)
        assertTrue(craft.instantMargin < 0)
    }

    /**
     * The mixed trade the Forge page shows: ingredients bought outright, result sold on an offer.
     *
     * It exists because a forge recipe already carries a wait - a median of six hours across the
     * 120 in the repo - so the minutes a sell offer takes cost nothing, while a buy order that has
     * to fill delays the slot itself. The measured asymmetry says the same: buying outright costs
     * 0.2% more at the median, where selling on an offer earns up to 6.7% more.
     */
    @Test
    fun `a forge recipe buys fast and sells patiently`() {
        val craft = price(
            Recipe("PLATE", 1.0, mapOf("ORE" to 10.0), durationSeconds = 6 * 3600),
            mapOf(
                // Ordering the ore costs 100 each; buying it outright costs 120.
                "ORE" to quote("ORE", topAsk = 100.0, instantBuy = 120.0),
                // Dumping the plate fetches 2000; a sell offer fetches 2600.
                "PLATE" to quote("PLATE", sellPrice = 2_000.0, topBid = 2_600.0),
            ),
        )!!

        // Instant both ways: 2000 - 1200 = 800.
        assertEquals(800.0, craft.instantProfit, 1e-6)
        // Patient both ways: 2600 - 1000 = 1600.
        assertEquals(1_600.0, craft.orderProfit, 1e-6)
        // The forge trade: bought outright at 1200, sold on an offer at 2600.
        assertEquals(1_400.0, craft.forgeProfit, 1e-6)
    }

    @Test
    fun `the forge figure sits between the two pure trades`() {
        // It has to: it takes the better exit and the worse entry, so it can beat neither the
        // fully patient trade nor lose to the fully hasty one. A figure outside that band would
        // mean the two sides had been crossed the wrong way round.
        val craft = price(
            Recipe("PLATE", 1.0, mapOf("ORE" to 4.0), durationSeconds = 3600),
            mapOf(
                "ORE" to quote("ORE", topAsk = 50.0, instantBuy = 90.0),
                "PLATE" to quote("PLATE", sellPrice = 500.0, topBid = 700.0),
            ),
        )!!

        assertTrue(
            craft.forgeProfit in craft.instantProfit..craft.orderProfit,
            "forge ${craft.forgeProfit} outside ${craft.instantProfit}..${craft.orderProfit}",
        )
    }

    @Test
    fun `an instant craft keeps the coherent pair, with no mixed trade offered`() {
        // Without a wait to exploit, buying outright to then queue a sell offer means paying for
        // speed and handing it straight back - a mistake rather than a strategy. So on a craft
        // the figure falls back to the patient trade rather than inventing a third column.
        val craft = price(
            Recipe("BLOCK", 1.0, mapOf("GEM" to 9.0)),
            mapOf(
                "GEM" to quote("GEM", topAsk = 10.0, instantBuy = 30.0),
                "BLOCK" to quote("BLOCK", sellPrice = 200.0, topBid = 260.0),
            ),
        )!!

        assertEquals(craft.orderProfit, craft.forgeProfit, 1e-6)
    }

    @Test
    fun `the forge margin is measured against the money that trade puts up`() {
        // Against the instant cost, since that is what this trade actually pays. Dividing by the
        // order cost would flatter it by exactly the premium it chose to pay.
        val craft = price(
            Recipe("PLATE", 1.0, mapOf("ORE" to 1.0), durationSeconds = 7200),
            mapOf(
                "ORE" to quote("ORE", topAsk = 100.0, instantBuy = 200.0),
                "PLATE" to quote("PLATE", sellPrice = 300.0, topBid = 400.0),
            ),
        )!!

        // 400 - 200 = 200 profit on 200 put up.
        assertEquals(200.0, craft.forgeProfit, 1e-6)
        assertEquals(100.0, craft.forgeMargin, 1e-6)
    }
}
