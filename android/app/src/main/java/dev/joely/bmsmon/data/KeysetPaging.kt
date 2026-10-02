package dev.joely.bmsmon.data

/**
 * Walk rows in bounded pages by keyset on a strictly increasing id (DATA-15/16): only one page is
 * ever materialized, and each page is an index range seek (`… AND id > :afterId ORDER BY id LIMIT
 * :limit`), so total cost is linear in the rows visited. Stops on the first short page. Pure (the
 * fetch is injected) so the paging rules are JVM-tested; a fetch that fails to advance throws
 * instead of looping forever.
 */
fun <T> forEachKeysetPage(
    pageSize: Int,
    fetch: (afterId: Long, limit: Int) -> List<T>,
    idOf: (T) -> Long,
    consume: (T) -> Unit,
) {
    require(pageSize > 0) { "pageSize must be positive" }
    var afterId = Long.MIN_VALUE
    while (true) {
        val page = fetch(afterId, pageSize)
        for (row in page) consume(row)
        if (page.size < pageSize) return
        val last = idOf(page.last())
        check(last > afterId) { "keyset did not advance past id=$afterId" }
        afterId = last
    }
}
