package scooper.taskqueue

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import scooper.data.App
import java.util.UUID

sealed class Task(val name: String, val id: UUID, val execute: suspend () -> Unit) {

    class Install(val app: App, id: UUID = UUID.randomUUID(), execute: suspend () -> Unit) :
        Task(app.uniqueName, id, execute)

    class Update(val app: App, id: UUID = UUID.randomUUID(), execute: suspend () -> Unit) :
        Task(app.uniqueName, id, execute)

    class Download(val app: App, id: UUID = UUID.randomUUID(), execute: suspend () -> Unit) :
        Task(app.uniqueName, id, execute)

    class Uninstall(val app: App, id: UUID = UUID.randomUUID(), execute: suspend () -> Unit) :
        Task(app.uniqueName, id, execute)

    class AddBucket(name: String, id: UUID = UUID.randomUUID(), execute: suspend () -> Unit) : Task(name, id, execute)

    class RemoveBucket(name: String, id: UUID = UUID.randomUUID(), execute: suspend () -> Unit) :
        Task(name, id, execute)

    class Refresh(name: String = "Refreshing", id: UUID = UUID.randomUUID(), execute: suspend () -> Unit) :
        Task(name, id, execute)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Task

        return name == other.name
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }

    override fun toString(): String {
        return when (this) {
            is Refresh -> "Refreshing"
            is AddBucket -> "Add Bucket: $name"
            is RemoveBucket -> "Remove Bucket: $name"
            is Download -> "Download: ${app.name}"
            is Install -> "Install: ${app.name}"
            is Uninstall -> "Uninstall: ${app.name}"
            is Update -> "Update: ${app.name}"
        }
    }
}

fun Task.toTitle(): AnnotatedString {
    return buildAnnotatedString {
        when (this@toTitle) {
            is Task.Refresh -> {
                append("Refreshing")
            }

            is Task.AddBucket -> {
                append("Add Bucket: ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(this@toTitle.name)
                }

            }

            is Task.RemoveBucket -> {
                append("Remove Bucket: ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(this@toTitle.name)
                }
            }

            is Task.Download -> {
                append("Download: ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(this@toTitle.app.name)
                }
            }

            is Task.Install -> {
                append("Install: ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(this@toTitle.app.name)
                }
            }

            is Task.Uninstall -> {
                append("Uninstall: ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(this@toTitle.app.name)
                }
            }

            is Task.Update -> {
                append("Update: ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(this@toTitle.app.name)
                }
            }
        }
    }
}