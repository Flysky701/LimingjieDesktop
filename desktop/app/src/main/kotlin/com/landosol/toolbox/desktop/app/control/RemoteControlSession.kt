package com.landosol.toolbox.desktop.app.control

/** Transport stays in WorkerLink; this adapter never owns a local Graph or device. */
class RemoteControlSession(private val link:WorkerLink):ControlSessionPort {
    override val instanceId get()=link.instanceId
    override val workerRunId get()=link.workerRunId
    override val snapshots get()=link.snapshots
    override val connection get()=link.connection
    override val error get()=link.error
    override suspend fun request(op:String,args:kotlinx.serialization.json.JsonObject)=link.request(op,args)
    override fun setSelected(selected:Boolean)=link.setSelected(selected)
    override suspend fun shutdown()=link.shutdown()
}
