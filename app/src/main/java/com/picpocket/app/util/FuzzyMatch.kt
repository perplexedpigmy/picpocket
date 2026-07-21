package com.picpocket.app.util

fun fuzzyMatch(query: String, target: String): Boolean {
    val q = query.lowercase()
    val t = target.lowercase()
    var qi = 0
    for (ti in t.indices) {
        if (qi < q.length && q[qi] == t[ti]) qi++
    }
    return qi == q.length
}
