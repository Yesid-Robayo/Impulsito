package io.github.yesidrobayo.shopledger.domain.rule.phrase

/**
 * Vocabulario en inglés de quien atiende un negocio pequeño. Los apóstrofos se escriben sin él:
 * `im`, `ill`.
 */
internal object EnglishVocabulary {
    private val verbs: Map<VerbKind, List<String>> = mapOf(
        VerbKind.SALE to listOf(
            "sold", "i sold", "we sold", "sale of", "sold to", "bought from me", "bought from us",
            "customer bought",
            "sale", "rang up", "rang", "charged", "i charged",
        ),
        VerbKind.PURCHASE to listOf(
            "bought", "i bought", "we bought", "purchased", "i purchased", "got", "i got",
            "restocked", "came in", "arrived", "delivery of", "brought", "i brought", "picked up",
        ),
        VerbKind.PRODUCTION to listOf(
            "made", "i made", "we made", "baked", "i baked", "we baked", "produced", "prepared",
            "cooked",
            "manufactured", "batch of", "a batch of", "out of the oven", "took out of the oven",
        ),
        VerbKind.COUNT to listOf(
            "counted", "i counted", "there are", "there is", "theres", "left", "i have", "we have",
            "remaining",
            "in stock", "stock count",
        ),
        VerbKind.DAMAGED to listOf(
            "damaged", "got damaged", "were damaged", "broke", "broken", "got broken", "i broke",
            "spoiled", "rotten",
            "went bad", "smashed",
        ),
        VerbKind.EXPIRED to listOf(
            "expired",
            "have expired",
            "went out of date",
            "out of date",
            "past the date",
            "past their date",
        ),
        VerbKind.LOST to listOf(
            "lost", "got lost", "were lost", "stolen", "got stolen", "were stolen", "stole",
            "someone stole",
            "went missing", "missing",
        ),
        VerbKind.OWN_USE to listOf(
            "i ate", "we ate", "ate", "i drank", "we drank", "own use", "personal use",
            "for personal use",
            "used at home", "we used at home", "for home", "took home", "for the house", "at home",
            "at the house",
        ),
        VerbKind.GIFT to listOf(
            "gave away", "gave", "i gave", "as a gift", "gift", "free sample", "as a sample",
            "as samples", "samples",
            "sample", "on the house", "for free",
        ),
        VerbKind.RECIPE_FOR to listOf(
            "to make",
            "to bake",
            "to prepare",
            "recipe for",
            "recipe of",
            "the recipe for",
            "the recipe of",
        ),
        VerbKind.RECIPE_USES to listOf(
            "takes", "needs", "uses", "i use", "we use", "contains", "has", "recipe", "i put",
            "we put",
        ),
        VerbKind.NEW_PRODUCT to listOf(
            "new product", "add product", "add a product", "add", "create product",
            "create a product", "create",
            "im going to sell", "i am going to sell", "going to sell", "i will sell",
            "ill start selling",
            "start selling", "register product",
        ),
        VerbKind.COST to listOf(
            "the cost of", "cost of", "cost for", "costs me", "cost me", "fix the cost of",
            "correct the cost of",
            "new cost", "i buy it at", "i buy it for", "is costing me", "are costing me",
            "costing me",
        ),
        VerbKind.PRICE to listOf(
            "the price of", "price of", "now costs", "now sells for", "raise", "raise the price of",
            "lower",
            "lower the price of", "is now", "are now", "new price", "change the price of", "costs",
            "sells for", "goes up to", "goes down to", "is going up to", "going up to", "now at",
            "is now at", "from now on", "starting today", "goes to", "up to",
        ),
        VerbKind.PAY_IN to
            listOf("paid me", "has paid me", "paid me back", "got paid", "gave me", "sent me"),
        VerbKind.PAY_OUT to
            listOf(
                "i paid", "we paid", "spent", "i spent", "we spent", "paid for", "transferred",
                "i transferred", "i sent", "topped up", "i topped up",
            ),
        VerbKind.PAY_ANY to listOf("paid", "payment", "has paid", "paid back"),
        VerbKind.RECEIVE to listOf("received", "i received", "we received"),
        VerbKind.TOOK to listOf("took"),
        VerbKind.TAKE to listOf("used", "i used", "we used", "took out"),
        VerbKind.RECIPE_YIELDS to
            listOf("i get", "we get", "makes", "yields", "gives me", "i make", "we make"),
        VerbKind.SETTLE_OUT to
            listOf("i paid off", "paid off", "i settled", "settled", "made a payment to"),
    )

    val vocabulary = PhraseVocabulary(
        fillers = listOf(
            "so", "well", "like", "er", "erm", "today", "yesterday", "this morning", "tonight",
            "please", "you know",
            "i mean", "actually", "just", "about", "around", "roughly", "more or less", "um", "umm",
            "uh", "uhm",
            "record that", "log that", "note that", "write down that", "write down",
        ),
        credit = listOf(
            "on credit", "on account", "on the tab", "on tab", "on my tab", "on her tab",
            "on his tab", "owes me",
            "still owes me", "will pay later", "pay later", "ill pay later", "she will pay later",
            "he will pay later",
            "paying later", "i owe", "i still owe",
        ),
        verbs = verbs,
        totalBefore = listOf(
            "all for", "in total", "total", "for all", "total of", "a total of", "for a total of",
            "altogether",
            "all together", "for everything", "came to",
        ),
        totalAfter = listOf(
            "in total",
            "total",
            "altogether",
            "all together",
            "for everything",
            "for all",
        ),
        unitPriceBefore = listOf("at", "@", "each at", "at a price of"),
        lineTotalBefore = listOf("for"),
        eachAfter = listOf(
            "each",
            "each one",
            "apiece",
            "a piece",
            "per unit",
            "a unit",
            "per piece",
        ),
        perUnitLeads = listOf("the", "per", "a", "by", "each"),
        amountFor = listOf("for", "on"),
        separators = listOf("and", "plus", "also", "and also", "as well as", "along with"),
        continuations = mapOf("and a half" to 500L, "and half" to 500L, "and a quarter" to 250L),
        fixedNumbers = mapOf(
            "a couple of" to 2_000L, "a couple" to 2_000L, "a pair of" to 2_000L,
            "a hundred" to 100_000L,
            "a thousand" to 1_000_000L, "a half" to 500L, "a quarter" to 250L, "half a" to 500L,
            "half an" to 500L,
        ),
        numberWords = wholeNumbers(
            "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
            "seven" to 7,
            "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
            "thirteen" to 13,
            "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
            "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
            "sixty" to 60,
            "seventy" to 70, "eighty" to 80, "ninety" to 90, "hundred" to 100, "thousand" to 1000,
        ) + mapOf("half" to 500L, "quarter" to 250L),
        multipliers = mapOf("hundred" to 100_000L),
        magnitudes = mapOf(
            "thousand" to 1_000_000L,
            "k" to 1_000_000L,
            "grand" to 1_000_000L,
            "million" to 1_000_000_000L,
            "millions" to 1_000_000_000L,
            "quarters" to 250L,
        ),
        conjunctions = listOf("and"),
        oneArticles = listOf("a", "an"),
        vague = listOf(
            "some", "several", "a few", "few", "a lot of", "lots of", "many", "a bunch of",
            "a little", "a little bit of",
        ),
        articles = listOf("the"),
        connectors = listOf("of", "a", "an"),
        stopwords = listOf(
            "of", "the", "a", "an", "and", "with", "in", "on", "for", "to", "at", "now", "is",
            "size", "flavor", "my", "store", "shop", "order",
        ),
        big = listOf("big", "large", "bigger", "larger", "grande"),
        small = listOf("small", "little", "smaller", "mini", "tiny"),
        partyPrepositions = listOf("to", "from", "at", "for", "with", "by"),
        unknownPartyPrepositions = listOf("to", "for", "from"),
        toPrepositions = listOf("to", "for"),
        honorifics = listOf("mr", "mrs", "ms", "miss", "mister", "sir", "dr", "madam"),
        paymentPrepositions = listOf(
            "in",
            "with",
            "by",
            "via",
            "using",
            "on",
            "paid with",
            "paid by",
        ),
        paymentSynonyms = mapOf(
            "cash" to listOf("cash"),
            "card" to listOf("card", "credit card", "debit card", "debit"),
            "transfer" to listOf("transfer", "bank transfer", "wire", "wire transfer", "deposit"),
            "wallet" to listOf("wallet", "digital wallet", "e wallet"),
        ),
        categorySynonyms = mapOf(
            "rent" to listOf("rent", "lease", "rental"),
            "utilities" to listOf(
                "electricity", "power", "water", "gas bill", "water bill", "power bill",
                "light bill", "electric bill",
                "utilities", "utility bill",
            ),
            "internet_phone" to
                listOf(
                    "internet",
                    "phone",
                    "phone bill",
                    "cell phone",
                    "cellphone",
                    "wifi",
                    "data plan", "top up", "recharge", "airtime",
                ),
            "transport" to listOf(
                "taxi", "cab", "gas", "gasoline", "fuel", "petrol", "bus", "bus fare", "fare",
                "parking", "toll",
                "shipping", "transport", "delivery", "courier",
            ),
            "packaging_supplies" to listOf("packaging", "supplies", "labels", "tape", "stickers"),
            "advertising" to listOf("advertising", "ads", "ad", "flyers", "marketing"),
            "taxes_fees" to listOf("taxes", "tax", "fees", "fee", "fine", "permit"),
            "wages" to listOf("wages", "salary", "payroll", "helper", "employee", "staff"),
        ),
        yieldLeads = listOf("with", "using"),
        pronounHeads = listOf("one", "ones"),
        questions = listOf("how", "what", "which", "who"),
        pronouns = listOf("i", "we", "they", "she", "he", "it", "you", "me", "us"),
        containers = listOf(
            "bottle", "bag", "jar", "can", "cup", "glass", "tray", "carton", "tub", "packet",
        ),
        productLead = listOf("product", "a product", "the product", "new", "called", "named"),
        priceKeywords = listOf(
            "price", "price of", "selling price", "sells for", "sell it for", "sell it at", "at",
            "for", "price is",
        ),
        costKeywords = listOf(
            "cost",
            "cost of",
            "costs me",
            "costs",
            "cost is",
            "i buy it at",
            "i buy it for",
        ),
        changeFillers = listOf("now", "is", "are", "will be", "new", "price", "cost"),
        singularRules = listOf(
            "^(.{2,})ies$" to "$1ie",
            "^(.{2,}[^aeiou])y$" to "$1ie",
            "^(.{1,}(ch|sh|x|ss|z|o))es$" to "$1",
            "^(.{2,}[^s])s$" to "$1",
        ),
        displayRules = listOf(
            "^(.{1,}rr)ies$" to "$1y",
            "^(.{2,}d)ies$" to "$1y",
            "^(.{2,})ies$" to "$1ie",
            "^(.{1,}(ch|sh|x|ss|z|o))es$" to "$1",
            "^(.{2,}[^s])s$" to "$1",
        ),
    )
}
