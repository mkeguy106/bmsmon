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

/**
 * Walk rows in bounded pages by a (timestamp, id) keyset (BLE-25) — for time-ordered reads where id
 * order isn't time order (a backward clock step interleaves ids). Starts at ([startTs], MIN), so the
 * first page holds rows with ts >= [startTs]; each next page starts after the previous page's last
 * (ts, id). Only one page is ever materialized. Stops on the first short page; a fetch that fails to
 * advance throws instead of looping forever.
 */
fun <T> forEachTsKeysetPage(
    pageSize: Int,
    startTs: Long,
    fetch: (afterTs: Long, afterId: Long, limit: Int) -> List<T>,
    tsOf: (T) -> Long,
    idOf: (T) -> Long,
    consume: (T) -> Unit,
) {
    require(pageSize > 0) { "pageSize must be positive" }
    var afterTs = startTs
    var afterId = Long.MIN_VALUE
    while (true) {
        val page = fetch(afterTs, afterId, pageSize)
        for (row in page) consume(row)
        if (page.size < pageSize) return
        val last = page.last()
        val ts = tsOf(last)
        val id = idOf(last)
        check(ts > afterTs || (ts == afterTs && id > afterId)) { "keyset did not advance past ($afterTs, $afterId)" }
        afterTs = ts
        afterId = id
    }
}
