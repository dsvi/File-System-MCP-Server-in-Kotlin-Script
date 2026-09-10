#!/usr/bin/env -S kotlinc -script

@file:Repository("https://repo.maven.apache.org/maven2")

// The bare multiplatform artifacts (e.g. kotlin-sdk-server) only contain common metadata, not JVM classes.
@file:DependsOn("io.modelcontextprotocol:kotlin-sdk-server-jvm:0.15.0")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-io-core-jvm:0.9.1")
@file:DependsOn("org.slf4j:slf4j-nop:2.0.18")

import io.ktor.utils.io.streams.asInput
import io.modelcontextprotocol.kotlin.sdk.server.*
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.json.*
import java.io.Reader
import java.nio.file.*
import java.nio.charset.MalformedInputException
import java.util.Comparator

// Domain error: thrown inside the tool handlers (argument checks and the implementation),
// caught by addSafeTool and reported through the MCP isError channel
// instead of as regular text content.
class ToolException(message: String) : Exception("Tool error: $message")

// Human-readable reason for the failure classes we know. NIO exceptions encode the
// real failure reason in their class (getMessage() is usually just the path), so
// they are spelled out here; null means "no special knowledge".
fun reasonFor(e: Throwable): String = when (e) {
    is FileAlreadyExistsException -> "file already exists"
    is NoSuchFileException -> "no such file or directory"
    is DirectoryNotEmptyException -> "directory is not empty"
    is AccessDeniedException -> "access denied (permission, or path is not a writable file)"
    is FileSystemNotFoundException -> "file system not found"
    is MalformedInputException -> "file is not valid UTF-8 (binary file or different encoding)"
    is InvalidPathException -> "invalid path"
    is FileSystemLoopException -> "symbolic link loop"
    else -> e.javaClass.simpleName
}

// Maps an exception to a human-readable diagnostic. Lazy NIO streams (Files.walk/
// list) and other wrappers hide the specific failure in the cause chain (e.g. as
// java.io.UncheckedIOException), and Files.readString reports invalid UTF-8 as a
// cryptic MalformedInputException, so the chain is walked and the deepest
// recognized exception is reported; when none is recognized, the outermost one is
// used, since it carries the most context. ToolException messages are already
// complete.

fun describeError(e: Exception): String {
    // Walk the cause chain: e.cause?.cause?.cause?... until null.
    val chain = generateSequence<Throwable>(e) { it.cause }
    return chain
        .mapIndexed { depth, t ->
            val reason = reasonFor(t)
            val detail = t.message?.let { ": $it" }.orEmpty()
            "${"  ".repeat(depth)}$reason$detail"
        }
        .joinToString(separator = "\n", postfix = "\n")
}

fun JsonObjectBuilder.prop(name: String, type: String, description: String) =
    putJsonObject(name) {
        put("type", type)
        put("description", description)
    }

fun JsonArrayBuilder.prop(type: String, description: String) =
    addJsonObject {
        put("type", type)
        put("description", description)
    }

// Typed argument access: request.str("x") / request.int("x") / request.bool("x")
// instead of request.arguments?.get("x")?.jsonPrimitive?....
// A missing or mistyped argument reads as null (the caller picks a default or an
// error). JsonNull must be intercepted before content: in kotlinx.serialization
// JsonNull.content is the string "null", not null.
fun JsonElement?.str(): String? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> content
    else -> null
}

fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull

fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

fun CallToolRequest.str(name: String): String? = arguments?.get(name)?.str()
fun CallToolRequest.int(name: String): Int? = arguments?.get(name)?.int()
fun CallToolRequest.bool(name: String): Boolean? = arguments?.get(name)?.bool()
fun CallToolRequest.required(name: String): String = str(name) ?: throw ToolException("$name is required")

// Fails the tool with a ToolException carrying a lazily-evaluated message, so the
// message string is only built when the condition actually fails.
fun requireTool(value: Boolean, lazyMessage: () -> String) {
    if (!value) throw ToolException(lazyMessage())
}

// Registers a tool whose handler is wrapped so that any exception (argument
// validation or implementation failure) becomes an isError CallToolResult
// instead of escaping the session.
fun Server.addSafeTool(
    name: String,
    description: String,
    inputSchema: ToolSchema,
    handler: suspend ClientConnection.(CallToolRequest) -> CallToolResult
) {
    addTool(
        name = name,
        description = description,
        inputSchema = inputSchema
    ) { request ->
        try {
            handler(this, request)
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent(describeError(e))), isError = true)
        }
    }
}

fun browseFs(directory: String): String {
    val p = Paths.get(directory)
    if (!Files.exists(p)) throw ToolException("'$p' does not exist.")
    if (!Files.isDirectory(p)) throw ToolException("'$p' is not a directory.")

    return Files.list(p).use { stream ->
        stream.map { entry ->
            try {
                val isDir = Files.isDirectory(entry)
                val size = if (isDir) 0L else Files.size(entry)
                "${if (isDir) "DIR " else "FILE"} ${size.toString().padStart(10)} ${entry.fileName}"
            } catch (e: Exception) {
                "UNKNOWN ${entry.fileName} (${describeError(e)})"
            }
        }.sorted().toList().joinToString("\n")
    }
}

// Creates the parent directories of p (if it has any), so callers can address
// files in not-yet-existing subdirectories.
fun ensureParent(p: Path) {
    p.parent?.let { Files.createDirectories(it) }
}

fun createFile(filePath: String, content: String) {
    val p = Paths.get(filePath)
    ensureParent(p)
    // CREATE_NEW opens with O_EXCL, so creation is atomic exclusive-create:
    // no exists-then-write race window, and FileAlreadyExistsException is the
    // authoritative "file exists" signal.
    Files.writeString(p, content, StandardOpenOption.CREATE_NEW)
}

fun writeFile(filePath: String, content: String, append: Boolean) {
    val p = Paths.get(filePath)
    ensureParent(p)
    if (append) {
        Files.writeString(p, content, StandardOpenOption.APPEND, StandardOpenOption.CREATE)
    } else {
        Files.writeString(p, content)
    }
}

fun deleteFile(filePath: String) {
    val p = Paths.get(filePath)
    deleteRecursively(p)
}

fun deleteRecursively(p: Path) {
    // Symlinks to directories are unlinked, not descended into (NOFOLLOW_LINKS).
    if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
        Files.delete(p)
        return
    }
    // rmdir(2) needs no permission on p itself, only write+execute on p's
    // parent, so an empty mode-000 subdir can be removed without ever
    // enumerating it. Only fall back to listing when the directory is
    // genuinely non-empty (ENOTEMPTY → DirectoryNotEmptyException).
    try {
        Files.delete(p)
    } catch (e: DirectoryNotEmptyException) {
        Files.newDirectoryStream(p).use { children ->
            for (child in children) deleteRecursively(child)
        }
        Files.delete(p)
    }
}

fun readLines(filePath: String, from: Int, to: Int?): String {
    val p = Paths.get(filePath)
    if (!Files.isRegularFile(p)) throw ToolException("'$p' does not exist or is not a file.")
    if (from < 0) throw ToolException("from ($from) must be non-negative.")
    if (to != null && to < from) throw ToolException("to ($to) cannot be less than from ($from).")

    val raw = Files.readString(p)
    val lines = splitLinesKeepends(raw)
    val count = lines.size

    if (from > count) throw ToolException("from ($from) is beyond the file's line count ($count).")
    val end = (to ?: count).coerceAtMost(count)
    val selected = lines.subList(from, end).joinToString(separator = "")
    return selected
}

fun splitLinesKeepends(s: String): List<String> {
    val content = s.replace("\r\n", "\n").replace('\r', '\n')
    // Equivalent of Python's str.splitlines(keepends=True) on universal-newline
    // text: splits on \n, \v, \f, \x1c-\x1e, \x85, \u2028, \u2029, keeping the line endings.
    val terminators = setOf('\n', '\u000B', '\u000C', '\u001C', '\u001D', '\u001E', '\u0085', '\u2028', '\u2029')
    val result = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < content.length) {
        if (content[i] in terminators) {
            result.add(content.substring(start, i + 1))
            start = i + 1
        }
        i += 1
    }
    if (start < content.length) result.add(content.substring(start))
    return result
}

data class Change(val from: Int, val to: Int, val content: String)

fun changeLines(filePath: String, changes: List<Change>) {
    if (changes.isEmpty()) return
    val p = Paths.get(filePath)
    requireTool(Files.exists(p)) { "'$p' does not exist." }

    // Validate and normalize changes
    for (i in changes.indices) {
        val fromLine = changes[i].from
        val toLine = changes[i].to
        requireTool(fromLine >= 0) {
            "Invalid change $i: line indices must be non-negative (from_line=$fromLine, to_line=$toLine)."
        }
        requireTool(toLine >= fromLine) {
            "Invalid change $i: to_line ($toLine) cannot be less than from_line ($fromLine)."
        }
    }

    // Check for overlapping or conflicting ranges
    val sortedChanges = changes.sortedWith(compareBy({ it.from }, { it.to }))
    for (i in 0 until sortedChanges.size - 1) {
        val currFrom = sortedChanges[i].from
        val currTo = sortedChanges[i].to
        val nextFrom = sortedChanges[i + 1].from
        if (nextFrom < currTo || (currFrom == currTo && nextFrom == currFrom)) {
            throw ToolException("Conflicting changes detected at or around line $currFrom. Ensure ranges do not overlap.")
        }
    }

    // Read with universal newlines, keeping line endings (like Python's readlines)
    val raw = Files.readString(p)
    val lines = splitLinesKeepends(raw).toMutableList()
    val fileLineCount = lines.size
    for (i in changes.indices) {
        val fromLine = changes[i].from
        val toLine = changes[i].to
        requireTool(fromLine <= fileLineCount) {
            "change $i: from_line ($fromLine) is beyond the file's line count ($fileLineCount)."
        }
        requireTool(toLine <= fileLineCount) {
            "change $i: to_line ($toLine) is beyond the file's line count ($fileLineCount)."
        }
    }

    // Apply changes from bottom to top to preserve line numbers of earlier changes
    for ((fromLine, toLine, newContent) in changes.sortedByDescending { it.from }) {
        // If inserting and the preceding line lacks a trailing newline, add one
        if (fromLine == toLine && fromLine in 1..lines.size) {
            val prev = lines[fromLine - 1]
            if (prev.isNotEmpty() && !prev.endsWith("\n")) {
                lines[fromLine - 1] = prev + "\n"
            }
        }

        val newLines = mutableListOf<String>()
        if (newContent.isNotEmpty()) {
            newLines.addAll(splitLinesKeepends(newContent))
            val last = newLines.size - 1
            if (!newLines[last].endsWith("\n")) newLines[last] = newLines[last] + "\n"
        }

        lines.subList(fromLine, toLine).clear()
        lines.addAll(fromLine, newLines)
    }

    // Write lines verbatim (like Python's writelines)
    Files.writeString(p, lines.joinToString(separator = ""))
}


data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val stdoutClamped: Boolean,
    val stderrClamped: Boolean,
)

suspend fun runShell(command: String, maxOutput: Int): ShellResult = coroutineScope {
    val process = ProcessBuilder(listOf("/bin/sh", "-c", command)).start()

    fun readClamped(reader: Reader): Pair<String, Boolean> {
        val buf = CharArray(maxOutput + 1)
        var total = 0
        while (total < buf.size) {
            val n = reader.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        val clamped = total > maxOutput
        return String(buf, 0, minOf(total, maxOutput)) to clamped
    }

    try {
        val stdoutDeferred = async(Dispatchers.IO) {
            process.inputStream.bufferedReader().use { readClamped(it) }
        }
        val stderrDeferred = async(Dispatchers.IO) {
            process.errorStream.bufferedReader().use { readClamped(it) }
        }
        val exitCode = process.onExit().await().exitValue()
        val (stdout, stdoutClamped) = stdoutDeferred.await()
        val (stderr, stderrClamped) = stderrDeferred.await()
        ShellResult(exitCode, stdout, stderr, stdoutClamped, stderrClamped)
    } finally {
        process.destroy()
    }
}

fun main() = runBlocking {
    // MCP over stdio requires stdout to carry JSON-RPC frames only.
    // Libraries (e.g. kotlin-logging) may print info messages to System.out
    // while the SDK initializes. Keep the real stdout handle for the protocol
    // transport and route any incidental prints to stderr instead.
    val protocolOut = System.out
    System.setOut(java.io.PrintStream(System.err, true))

    val server = Server(
        Implementation(
            name = "fs_mcp_server",
            version = "1.0.0"
        ),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        )
    )

    server.addSafeTool(
        name = "browse_fs",
        description = "List files and directories in the specified directory.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                prop("directory", "string", "Directory path to list. Defaults to '.'")
            }
        )
    ) { request ->
        CallToolResult(content = listOf(TextContent(browseFs(request.str("directory") ?: "."))))
    }

    server.addSafeTool(
        name = "create_file",
        description = "Create a new file. Fails if the file already exists.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                prop("file_path", "string", "Path to the file to create.")
                prop("content", "string", "Content to write to the new file.")
            },
            required = listOf("file_path", "content")
        )
    ) { request ->
        createFile(request.required("file_path"), request.required("content"))
        CallToolResult(content = emptyList())
    }

    server.addSafeTool(
        name = "write_file",
        description = "Write content to a file. Creates the file if it does not exist.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                prop("file_path", "string", "Path to the file to write.")
                prop("content", "string", "Content to write to the file.")
                prop("append", "boolean", "Append to the file if it exists instead of replacing it. Defaults to false.")
            },
            required = listOf("file_path", "content")
        )
    ) { request ->
        writeFile(request.required("file_path"), request.required("content"), request.bool("append") ?: false)
        CallToolResult(content = emptyList())
    }

    server.addSafeTool(
        name = "delete_file",
        description = "Delete a file or an entire directory.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                prop("file_path", "string", "Path to the file or directory to delete.")
            },
            required = listOf("file_path")
        )
    ) { request ->
        deleteFile(request.required("file_path"))
        CallToolResult(content = emptyList())
    }

    server.addSafeTool(
        name = "read_lines",
        description = "Read the contents of a file by 0-based line numbers, using a half-open [from, to) range. Both from and to are optional; an omitted `to` means read till the end of the file.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                prop("file_path", "string", "Path to the file to read.")
                prop("from", "integer", "0-based start line index (inclusive). Defaults to 0.")
                prop("to", "integer", "0-based end line index (exclusive). Omit to read till the end of the file.")
            },
            required = listOf("file_path")
        )
    ) { request ->
        CallToolResult(
            content = listOf(TextContent(readLines(request.required("file_path"), request.int("from") ?: 0, request.int("to"))))
        )
    }

    server.addSafeTool(
        name = "change_lines",
        description = """
            Replace, insert, or delete multiple line ranges in a file in a single operation.
            Each change is a tuple of (from_line, to_line, new_content) using 0-based line indexing
            and half-open ranges [from_line, to_line).

            - To replace lines: Provide from_line < to_line and new_content.
            - To insert lines: Provide from_line == to_line and new_content (inserts before from_line).
            - To delete lines: Provide from_line < to_line and an empty new_content.

            WARNING: Do NOT call this tool in parallel on same file. Pack several edits to same file into the array
        """.trimIndent(),
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                prop("file_path", "string", "Path to the file to modify.")
                putJsonObject("changes") {
                    put("type", "array")
                    put("description", "List of changes, each a [from_line, to_line, new_content] tuple.")
                    put("minItems", 1)
                    putJsonObject("items") {
                        put("type", "array")
                        put("minItems", 3)
                        put("maxItems", 3)
                        putJsonArray("prefixItems") {
                            prop("integer", "from_line: 0-based start line index (inclusive).")
                            prop("integer", "to_line: 0-based end line index (exclusive).")
                            prop("string", "new_content: New content to insert or replace. Empty string to delete the lines.")
                        }
                    }
                }
            },
            required = listOf("file_path", "changes")
        )
    ) { request ->
        val changesArg = request.arguments?.get("changes")
            ?: throw ToolException("changes is required")
        val changes = (changesArg as? JsonArray)
            ?: throw ToolException("changes must be an array of [from_line, to_line, new_content] tuples.")
        val parsed = changes.mapIndexed { i, element ->
            val tuple = element as? JsonArray
                ?: throw ToolException("change $i must be a 3-tuple [from_line, to_line, new_content].")
            if (tuple.size != 3) {
                throw ToolException("change $i must be a 3-tuple [from_line, to_line, new_content].")
            }
            val from = tuple[0].int()
                ?: throw ToolException("change $i: from_line and to_line must be integers.")
            val to = tuple[1].int()
                ?: throw ToolException("change $i: from_line and to_line must be integers.")
            val content = when (val c = tuple[2]) {
                is JsonPrimitive -> c.content
                else -> throw ToolException("change $i: new_content must be a string.")
            }
            Change(from, to, content)
        }
        changeLines(request.required("file_path"), parsed)
        CallToolResult(content = emptyList())
    }

    server.addSafeTool(
        name = "run_shell",
        description =
            "Execute a shell command in the current working directory. Returns exit_code, stdout, and stderr as three separate text content items;" +
            " stdout and stderr are each clamped to max_output characters (default 1000), and when clamped the item is labeled 'stdout (clamped):' or 'stderr (clamped):'.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                prop("command", "string", "The shell command to execute.")
                prop("max_output", "integer", "Maximum number of characters returned for stdout and stderr (each). Defaults to 1000.")
            },
            required = listOf("command")
        )
    ) { request ->
        val maxOutput = request.int("max_output") ?: 1000
        if (maxOutput < 0) throw ToolException("max_output must not be negative.")
        val result = runShell(request.required("command"), maxOutput)
        CallToolResult(
            content = listOf(
                TextContent("exit_code: ${result.exitCode}"),
                TextContent("${if (result.stdoutClamped) "stdout (clamped)" else "stdout"}: ${result.stdout}"),
                TextContent("${if (result.stderrClamped) "stderr (clamped)" else "stderr"}: ${result.stderr}")
            )
        )
    }

    val transport = StdioServerTransport(
        input = System.`in`.asInput(),
        output = protocolOut.asSink().buffered()
    )

    val session = server.createSession(transport)
    val done = Job()
    session.onClose { done.complete() }
    done.join()
}

main()
