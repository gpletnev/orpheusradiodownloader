/*
 * Copyright (c) 2019.
 * Grigory Pletnev
 * gpletnev@gmail.com
 */

import java.util.*

data class Archive(
    val url: String,
    val programs: List<Program>
)

data class Program(
    val url: String,
    val title: String,
    val artist: String?,
    var list: List<Record> = LinkedList()
)

data class Record(
    var trackNumber: Int = 0,
    var title: String? = null,
    val artist: String? = null,
    val album: String,
    val url: String,
    val date: Date? = null,
    var audioUrl: String? = null,
    var imgUrl: String? = null
)