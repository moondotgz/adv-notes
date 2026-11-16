package com.advnotes.app.render

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.nio.file.Files
import java.security.MessageDigest

/** A marketplace repository on disk, served over HTTP on localhost. */
class FakeMarketplace(tamper: Boolean = false) : AutoCloseable {
    val root: File = Files.createTempDirectory("market").toFile()
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val baseUrl get() = "http://127.0.0.1:${server.localPort}/"

    init {
        tool("hello", "Hello tool", "1.2.0", "Study", "Says hello and writes it on the page.", "functions")
        tool("periodic", "Periodic table", "2.0.0", "Chemistry", "Element data at a glance.", "science")
        tool("timer", "Lab timer", "1.0.0", "Study", "Time experiments.", "timer")
        val entries = File(root, "tools").listFiles()!!.sortedBy { it.name }.joinToString(",") { dir ->
            val m = File(dir, "manifest.json").readText().trim().removeSuffix("}")
            val files = dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.joinToString(",") { f ->
                val bytes = f.readBytes()
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                """{"path":"${f.relativeTo(dir).path}","size":${bytes.size},"sha256":"${if (tamper) "0".repeat(64) else sha}"}"""
            }
            """$m,"path":"tools/${dir.name}","files":[$files]}"""
        }
        File(root, "index.json").writeText("""{"format":1,"tools":[$entries]}""")
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    sock.use { c ->
                        val r = c.getInputStream().bufferedReader()
                        val path = r.readLine().orEmpty().split(' ').getOrElse(1) { "/" }
                        while (r.readLine().orEmpty().isNotEmpty()) { /* headers */ }
                        val f = File(root, java.net.URLDecoder.decode(path.removePrefix("/"), "UTF-8"))
                        val o = c.getOutputStream()
                        if (f.isFile) {
                            val b = f.readBytes()
                            o.write("HTTP/1.1 200 OK\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); o.write(b)
                        } else o.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        o.flush()
                    }
                }
            }
        }
    }

    private fun tool(id: String, name: String, version: String, category: String, desc: String, icon: String) {
        val d = File(root, "tools/$id").apply { mkdirs() }
        File(d, "manifest.json").writeText(
            """{"id":"$id","name":"$name","description":"$desc","icon":"$icon","version":"$version","author":"Adv Notes","category":"$category"}"""
        )
        File(d, "index.html").writeText("<!doctype html><script src=\"../lib/advnotes.js\"></script><h1>$name</h1>")
        File(d, "assets").mkdirs()
        File(d, "assets/data.json").writeText("""{"answer":42}""")
    }

    override fun close() { server.close(); root.deleteRecursively() }
}
