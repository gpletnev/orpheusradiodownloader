/*
 * Copyright (c) 2019.
 * Grigory Pletnev
 * gpletnev@gmail.com
 */

import com.google.gson.GsonBuilder
import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.*

private const val root = "http://muzcentrum.ru"
private const val programsArchive = "http://muzcentrum.ru/orpheusradio/programsarchive"
private val simpleDateFormat = SimpleDateFormat("dd.MM.yyyy")
private val outSimpleDateFormat = SimpleDateFormat("yyyy.MM.dd")

fun main(args: Array<String>) = runBlocking {

    val gson = GsonBuilder().setPrettyPrinting().create()

    when (args.count()) {
        0 -> { // list all programs in archive
            val archive: Archive = getArchive()

            with(archive) {
                programs.forEachIndexed { index, program ->
                    println("$index. ${program.url.substringAfterLast("/")} - ${program.title}")
                }
            }
        }
        1 -> { // list tracks in program. args[0] is the name of program for ex. eurofest
            val archive: Archive
            if (args[0] == "all") {
                archive = getArchive()
                archive.programs.forEachIndexed { index, program ->
                    println("$index. ${program.url.substringAfterLast("/")} - ${program.title}")
                    println(program.url)
                    if (program.list.isEmpty()) {
                        program.list = getRecords(program)
                    }
                    program.list.forEach {
                        println(it)
                    }
                    println()
                }
            } else {
                archive = getProgramsArchive() ?: getArchive()
                val program = archive.programs.first { it.url.substringAfterLast("/") == args[0] }
                if (program.list.isEmpty()) {
                    program.list = getRecords(program)
                }
                program.list.forEach {
                    println(it)
                }
                println()
            }
            val archiveJson = gson.toJson(archive)
            val file = File("programsarchive.json")
            file.writeText(archiveJson)
        }
        2 -> { // creates mp3 files based on already collected in archive.json data to path
            val path = args[1]
            val root = File(path)
            if (root.exists()) {
                try {
                    val archive: Archive? = getProgramsArchive()
                    if (archive != null) {
                        val program = archive.programs.find { it.url.substringAfterLast("/") == args[0] }
                        if (program != null) {
                            if (!program.list.isEmpty()) {
                                val programPath = "${root.absolutePath}${File.separatorChar}${args[0]}"
                                val programDir = File(programPath)
                                programDir.mkdir()
                                program.list.forEach {
                                    println(it.saveMp3(programPath))
                                }
                            } else {
                                println("Program ${program.title} doesn't have any records. Scan it first")
                            }
                        } else {
                            println("No program ${args[0]} in programs archive")
                        }
                    } else {
                        println("Get programs archive at first")
                    }

                } catch (e: Exception) {
                    println(e.localizedMessage)
                }

            } else {
                println("Path $path does not exist")
            }

        }
        else -> println("Help")
    }
}

fun Record.saveMp3(dir: String): String {
    val audiopath = "$dir${File.separatorChar}${audioUrl?.substringAfterLast("/")}"
    Files.copy(URL(audioUrl).openStream(), Paths.get(audiopath))

    val mp3File = Mp3File(audiopath)

    if (!mp3File.hasId3v2Tag()) {
        mp3File.id3v2Tag = ID3v24Tag()
    }

    with(mp3File) {
        id3v2Tag.track = trackNumber.toString()
        id3v2Tag.title = title
        id3v2Tag.artist = artist
        id3v2Tag.album = album
        id3v2Tag.url = url
        id3v2Tag.date = outSimpleDateFormat.format(date)
        id3v2Tag.setAlbumImage(URL(imgUrl).openStream().readBytes(), "image/jpg")
    }

    val newFileName = dir.plus(File.separator).plus("${trackNumber}_${url.substringAfterLast("/")}").plus(".mp3")
    mp3File.save(newFileName)
    File(audiopath).delete()
    return newFileName
}

fun getArchive(): Archive {
    val gson = GsonBuilder().setPrettyPrinting().create()
    val file = File("archive.json")
    var archive: Archive? = null

    try {
        println("Trying to read archive from ${file.absoluteFile}")
        archive = gson.fromJson(file.readText(), Archive::class.java)
    } catch (e: Exception) {
        println("Error in reading archive from ${file.absoluteFile}")
    }

    if (archive == null) {
        println("Read archive from $programsArchive")
        archive = Archive(programsArchive, programsarchive())
        file.writeText(gson.toJson(archive))
    }
    return archive
}

fun getProgramsArchive(): Archive? {
    val gson = GsonBuilder().setPrettyPrinting().create()
    val file = File("programsarchive.json")
    var archive: Archive? = null

    try {
        println("Trying to read programs archive from ${file.absoluteFile}")
        archive = gson.fromJson(file.readText(), Archive::class.java)
    } catch (e: Exception) {
        println("Error in reading programs archive from ${file.absoluteFile}")
    }
    return archive
}

fun programsarchive(): List<Program> {
    val list = LinkedList<Program>()
    Jsoup.connect(programsArchive).get().select("div.afisha-list-item")
        .forEach {
            val aitTxt = it.select("div.ait-txt").first()
            val a = aitTxt.getElementsByAttribute("href").first()
            val url = "$root${a.attr("href")}"
            val title = a.text()
            val artist: String? = aitTxt.getElementsByTag("p")?.first()?.text()
            list.add(Program(url, title, artist))
        }
    return list
}

fun getRecords(
    program: Program,
    startPage: Int = 0,
    endPage: Int = getLastPageNumber(program.url)
): List<Record> {
    val records: LinkedList<Record> = LinkedList()

    for (page in startPage..endPage step 10) {

        val blog = Jsoup.connect("${program.url}?start=$page").get().selectFirst("div.blog")
        blog.getElementsByAttributeValue("itemprop", "blogPost").forEach {
            val dateString = it.selectFirst("div.page-header").text()
            val date = simpleDateFormat.parse(dateString)

            val a = it.select("div.ait-txt").first().getElementsByAttribute("href").first()
            val link = "$root${a.attr("href")}"

            val thumbnail =
                it.selectFirst("div.ait-pic")?.getElementsByAttributeValue("itemprop", "thumbnailUrl")?.first()
                    ?.attr("src")
            records.add(
                Record(
                    url = link,
                    date = date,
                    imgUrl = if (thumbnail != null && !thumbnail.startsWith("http")) "$root$thumbnail" else thumbnail,
                    album = program.title,
                    artist = program.artist
                )
            )
        }

        blog.selectFirst("div.items-more")?.select("a[href]")?.forEach {
            val link = "$root${it.attr("href")}"
            records.add(
                Record(
                    url = link,
                    album = program.title,
                    artist = program.artist
                )
            )
        }
    }
    records.reverse()
    val iterator = records.listIterator()
    var index = 0
    for (record in iterator) {
        val document = Jsoup.connect(record.url).get()
        val item = document.getElementById("col-l")
        val title = item.getElementsByAttributeValue("itemprop", "name").first().text()
        val list = getAudioUrlAndDescription(document.head())
        var audioUrl = ""
        when (list.size) {
            0 -> {
                val iframe = item.getElementsByTag("iframe")
                if (iframe.isNotEmpty()) {
                    val frameSrc = iframe.first().attr("src")
                    val body = if (frameSrc.startsWith("http")) Jsoup.connect(frameSrc).get().body() else null
                    audioUrl = body?.selectFirst("a[title]")?.attr("href") ?: ""
                }

                if (audioUrl.isNotEmpty()) {
                    record.trackNumber = ++index
                    record.title = title
                    record.audioUrl = audioUrl
                } else {
                    iterator.remove()
                }
            }
            1 -> {
                audioUrl = list.first().first
                record.trackNumber = ++index
                record.title = title
                record.audioUrl = audioUrl
            }
            else -> {
                record.trackNumber = ++index
                record.title = list.first().second ?: title
                record.audioUrl = list.first().first

                list.removeFirst()
                list.forEach {
                    val newRecord = Record(
                        ++index,
                        it.second,
                        record.artist,
                        record.album,
                        record.url,
                        record.date,
                        it.first,
                        record.imgUrl
                    )
                    iterator.add(newRecord)
                }
            }
        }
    }

    return records
}

fun getAudioUrlAndDescription(head: Element): LinkedList<Pair<String, String?>> {
    val list = LinkedList<Pair<String, String?>>()
    val myPlayerRegex = Regex("MyPlayer\\(\\{[^}]+}")
    val srcRegex = Regex("src: '(.*)'")
    val descriptionRegex = Regex("description: '(.*)'")

    val functions = head.getElementsByAttributeValue("type", "text/javascript")

    for (func in functions) {
        val data = func.html()
        if (data.isNotEmpty()) {
            val myPlayerMatches = myPlayerRegex.findAll(data)
            for (match in myPlayerMatches) {
                val src = srcRegex.find(match.value)!!.value.substringAfter(":").trim().replace("'", "")
                val description =
                    descriptionRegex.find(match.value)?.value?.substringAfter(":")?.trim()?.replace("'", "")
                val audioUrl = if (!src.startsWith("http")) "$root$src" else src
                list.add(Pair(audioUrl, description))
            }
        }
    }
    return list
}

fun getLastPageNumber(url: String): Int {
    val document = Jsoup.connect(url).get()
    var paginationList = document.select("ul.pagination-list")

    if (paginationList.isNotEmpty()) {
        paginationList = paginationList[0]?.getElementsByTag("li")
        return paginationList.last().getElementsByAttribute("href").first().attr("href").substringAfter("?start=")
            .toInt()
    }
    return 0
}