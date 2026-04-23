package scooper.repository.db

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import scooper.data.AppStatus

class AppEntity(id: EntityID<Int>) : Entity<Int>(id) {
    companion object : EntityClass<Int, AppEntity>(Apps)

    var name by Apps.name
    var version by Apps.version
    var latestVersion by Apps.latestVersion
    var bucket by BucketEntity optionalReferencedOn Apps.bucketId
    var global by Apps.global
    
    private var _status by Apps.status
    var status: AppStatus
        get() = AppStatus.fromString(_status)
        set(value) {
            _status = value.name.lowercase()
        }

    var description by Apps.description
    var url by Apps.url
    var homepage by Apps.homepage
    var license by Apps.license
    var createAt by Apps.createAt
    var updateAt by Apps.updateAt
}

class BucketEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<BucketEntity>(Buckets)

    var name by Buckets.name
    var url by Buckets.url
}
