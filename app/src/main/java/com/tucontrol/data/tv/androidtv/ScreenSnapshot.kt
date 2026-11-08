package com.tucontrol.data.tv.androidtv

import com.tucontrol.domain.model.AppNames

/** What the TV is showing: its visible texts and focusable elements (from a UI hierarchy dump). */
class ScreenSnapshot(val nodes: List<Node>) {

    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
        val area: Long get() = (right - left).toLong() * (bottom - top)

        fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom

        fun containsCenterOf(node: Node): Boolean = contains(node.bounds.centerX, node.bounds.centerY)
    }

    data class Node(val text: String, val bounds: Bounds, val focusable: Boolean, val focused: Boolean)

    val texts: List<String> get() = nodes.map { it.text }.filter { it.isNotBlank() }

    /**
     * The elements the D-pad moves between. List and scroll containers are left out (they hold
     * several rows); a row that holds a single control (e.g. "Modo de imagen · Inteligente")
     * stays an item.
     */
    val items: List<Node> by lazy {
        val focusables = nodes.filter { it.focusable }
        focusables
            .filterNot { outer -> focusables.count { inner -> inner !== outer && outer.bounds.containsCenterOf(inner) } > 1 }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    /** The highlighted item, if the TV is in D-pad (not touch) mode. */
    val focusedItem: Node? get() = items.filter { it.focused }.minByOrNull { it.bounds.area }

    /**
     * The selectable text that best names [target] ("kalley" → "Kalley K-BSK40"), if any.
     * Titles and other text outside any D-pad item are ignored: they cannot be selected.
     */
    fun find(target: String): Node? = nodes
        .filter { it.text.isNotBlank() && AppNames.names(target, it.text) && itemOf(it) != null }
        .maxWithOrNull(compareBy({ AppNames.coverage(target, it.text) }, { -it.text.length }))

    /** The D-pad item that holds [node]: the smallest one around it (its row in a list). */
    fun itemOf(node: Node): Node? =
        items.filter { it.bounds.contains(node.bounds.centerX, node.bounds.centerY) }.minByOrNull { it.bounds.area }

    /**
     * Rows of items whose vertical centers fall in [range]; items that overlap vertically (a row
     * and the control inside it) are one row.
     */
    fun rowsBetween(range: IntRange): Int {
        val bands = items.filter { it.bounds.centerY in range }.map { it.bounds.top..it.bounds.bottom }.sortedBy { it.first }
        var rows = 0
        var lastEnd = Int.MIN_VALUE
        for (band in bands) {
            if (band.first >= lastEnd) rows++
            lastEnd = maxOf(lastEnd, band.last)
        }
        return rows
    }

    companion object {
        private val NODE = Regex("<node [^>]*>")

        fun parse(xml: String): ScreenSnapshot = ScreenSnapshot(
            NODE.findAll(xml).mapNotNull { match ->
                val node = match.value
                val bounds = attribute(node, "bounds")?.let(::parseBounds) ?: return@mapNotNull null
                Node(
                    text = unescape(attribute(node, "text").orEmpty()),
                    bounds = bounds,
                    focusable = attribute(node, "focusable") == "true",
                    focused = attribute(node, "focused") == "true",
                )
            }.toList(),
        )

        private fun attribute(node: String, name: String): String? =
            Regex(" $name=\"([^\"]*)\"").find(node)?.groupValues?.get(1)

        /** "[220,334][900,461]" */
        private fun parseBounds(value: String): Bounds? {
            val numbers = Regex("-?\\d+").findAll(value).map { it.value.toInt() }.toList()
            return if (numbers.size == 4) Bounds(numbers[0], numbers[1], numbers[2], numbers[3]) else null
        }

        private fun unescape(text: String): String = text
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
}
