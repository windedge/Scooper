package scooper.taskqueue

import scooper.data.App
import java.util.UUID

sealed class Task(val name: String, val id: UUID, val execute: suspend () -> Unit) {



    class Install(app: App, id: UUID = UUID.randomUUID(), execute: suspend () -> Unit) : Task(app.uniqueName, id, execute)

    class Update(app: App, id: UUID = UUID.randomUUID(), execute: suspend () -> Unit) :
        Task(app.uniqueName, id, execute)

    class Download(app: App, id: UUID = UUID.randomUUID(), execute: suspend () -> Unit) :
        Task(app.uniqueName, id, execute)

    class Uninstall(app: App, id: UUID = UUID.randomUUID(), execute: suspend () -> Unit) :
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
}