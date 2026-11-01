/*
 * Copyright 2026 Duck Apps Contributor
 * If you have any questions, suggestions, or other inquiries, please email Eltavine <me@eltavine.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.eltavine.duckdetector.features.dangerousapps.data.repository

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.TimeUnit

internal fun enumerateAndroidDirsByListing(): Set<String> {
    val dirs = linkedSetOf<String>()
    listOf("/sdcard/Android/data", "/sdcard/Android/obb").forEach { targetPath ->
        runCatching {
            File(targetPath)
                .listFiles()
                ?.filter { it.isDirectory }
                ?.mapTo(dirs) { it.name }
        }
        dirs += execDirectoryListing("ls", targetPath)
    }
    return dirs
}

internal fun enumerateAndroidDirsByZeroWidthBypass(): Set<String> {
    val basePath = "/sdcard/Android/data/"
    val bypassPath = basePath.dropLast(1) + ZERO_WIDTH_SPACE + basePath.last()
    return execDirectoryListing("ls", bypassPath)
}

internal fun enumerateAndroidDirsByIgnorableCodePoints(): Set<String> {
    val dirs = linkedSetOf<String>()
    val targetDirs = listOf("/sdcard/Android/data", "/sdcard/Android/obb")

    targetDirs.forEach { targetPath ->
        for (bypassChar in IGNORABLE_CODE_POINTS) {
            if (dirs.size > 50) {
                break
            }
            val bypassPaths = listOf(
                "$targetPath$bypassChar/",
                "/sdcard/${bypassChar}Android/${targetPath.substringAfterLast("/")}",
                "/sdcard$bypassChar/Android/${targetPath.substringAfterLast("/")}",
            )
            bypassPaths.forEach { bypassPath ->
                dirs += execDirectoryListing("ls", bypassPath, timeoutSeconds = 1L)
                if (dirs.isNotEmpty()) {
                    return@forEach
                }
            }
            if (dirs.isNotEmpty()) {
                break
            }
        }
    }

    return dirs
}

private fun execDirectoryListing(
    vararg command: String,
    timeoutSeconds: Long = PROCESS_TIMEOUT_SECONDS,
): Set<String> {
    var process: Process? = null
    return try {
        process = ProcessBuilder(command.toList())
            .redirectErrorStream(true)
            .start()
        val result = linkedSetOf<String>()
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val dirName = line.trim()
                if (dirName.isNotEmpty() && dirName != "." && dirName != "..") {
                    result += dirName
                }
            }
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return emptySet()
        }
        result
    } catch (_: Exception) {
        emptySet()
    } finally {
        process?.destroy()
    }
}

internal fun checkFuseDataPath(packageName: String, diagnosticSink: ((String) -> Unit)? = null): Boolean {
    val paths = listOf(
        "/storage/emulated/0/Android/data/$packageName",
        "/storage/emulated/0/Android/obb/$packageName",
    )
    // Preserve the original verdict and its short-circuit evaluation.
    val detected = paths.any { path ->
        runCatching {
            File(path).exists() && File(path).isDirectory
        }.getOrDefault(false)
    }
    if (packageName == "com.sukisu.ultra") {
        // Independent diagnostic samples must not feed back into the verdict.
        paths.forEach { path ->
            val exists = runCatching { File(path).exists() }
            val isDirectory = runCatching { File(path).isDirectory }
            runCatching {
                android.util.Log.i(
                    "DuckFsDiag",
                    "uid=${android.os.Process.myUid()} path=$path " +
                        "exists=${exists.getOrNull()} isDirectory=${isDirectory.getOrNull()} " +
                        "existsError=${exists.exceptionOrNull()?.javaClass?.simpleName} " +
                        "isDirectoryError=${isDirectory.exceptionOrNull()?.javaClass?.simpleName}",
                )
            }
            // Supplemental in-process libc probes. They run only AFTER the original
            // verdict, never influence it, and never attach a tracer to the app.
            val stat = runCatching { Os.stat(path) }
            val lstat = runCatching { Os.lstat(path) }
            val access = runCatching { Os.access(path, OsConstants.F_OK) }
            val statMode = stat.getOrNull()?.st_mode
            val lstatMode = lstat.getOrNull()?.st_mode
            val observation = "uid=${android.os.Process.myUid()} path=$path " +
                "exists=${exists.getOrNull()} isDirectory=${isDirectory.getOrNull()} " +
                "statOk=${stat.isSuccess} " +
                "statDir=${statMode?.let(OsConstants::S_ISDIR)} " +
                "statErrno=${(stat.exceptionOrNull() as? ErrnoException)?.errno} " +
                "lstatOk=${lstat.isSuccess} " +
                "lstatDir=${lstatMode?.let(OsConstants::S_ISDIR)} " +
                "lstatErrno=${(lstat.exceptionOrNull() as? ErrnoException)?.errno} " +
                "access=${access.getOrNull()} " +
                "accessErrno=${(access.exceptionOrNull() as? ErrnoException)?.errno}"
            // Export diagnostic observations separately; never use them to change detection.
            runCatching { diagnosticSink?.invoke(observation) }
            runCatching { android.util.Log.i("DuckFsDiagSys", observation) }
        }
    }
    return detected
}

internal fun checkPathExists(path: String): Boolean {
    if (runCatching { File(path).exists() }.getOrDefault(false)) {
        return true
    }
    var process: Process? = null
    return try {
        process = ProcessBuilder(listOf("test", "-e", path))
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            false
        } else {
            process.exitValue() == 0
        }
    } catch (_: Exception) {
        false
    } finally {
        process?.destroy()
    }
}

internal fun detectSharedStorageBaselineDenied(): Boolean {
    return SHARED_STORAGE_BASELINE_PATHS.all { path ->
        try {
            Os.stat(path)
            false
        } catch (e: ErrnoException) {
            e.errno == OsConstants.EACCES || e.errno == OsConstants.EPERM
        } catch (_: Exception) {
            false
        }
    }
}

private const val PROCESS_TIMEOUT_SECONDS = 5L
private const val ZERO_WIDTH_SPACE = "\u200B"

private val IGNORABLE_CODE_POINTS = listOf(
    "\u00AD",
    "\uFE02",
    "\uFE0F",
    "\uFEFF",
    "\uFFA0",
)

private val SHARED_STORAGE_BASELINE_PATHS = listOf(
    "/sdcard",
    "/sdcard/Android",
    "/sdcard/DCIM",
    "/sdcard/Download",
    "/sdcard/Pictures",
)
