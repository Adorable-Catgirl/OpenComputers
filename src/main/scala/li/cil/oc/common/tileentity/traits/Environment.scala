package li.cil.oc.common.tileentity.traits

import li.cil.oc.Settings
import li.cil.oc.api.driver
import li.cil.oc.api.network
import li.cil.oc.api.network.Connector
import li.cil.oc.api.network.SidedEnvironment
import li.cil.oc.common.EventHandler
import li.cil.oc.util.ExtendedNBT._
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.util.EnumFacing

trait Environment extends TileEntity with network.Environment with driver.EnvironmentHost {
  protected var isChangeScheduled = false

  override def xPosition = x + 0.5

  override def yPosition = y + 0.5

  override def zPosition = z + 0.5

  override def markChanged() = if (canUpdate) isChangeScheduled = true else world.markChunkDirty(getPos, this)

  protected def isConnected = node.address != null && node.network != null

  // ----------------------------------------------------------------------- //

  override protected def initialize() {
    super.initialize()
    if (isServer) {
      EventHandler.schedule(this)
    }
  }

  override def updateEntity() {
    super.updateEntity()
    if (isChangeScheduled) {
      world.markChunkDirty(getPos, this)
      isChangeScheduled = false
    }
  }

  override def dispose() {
    super.dispose()
    if (isServer) {
      Option(node).foreach(_.remove)
      this match {
        case sidedEnvironment: SidedEnvironment => for (side <- EnumFacing.values) {
          Option(sidedEnvironment.sidedNode(side)).foreach(_.remove())
        }
        case _ =>
      }
    }
  }

  // ----------------------------------------------------------------------- //

  override def readFromNBT(nbt: NBTTagCompound) {
    super.readFromNBT(nbt)
    if (node != null && node.host == this) {
      node.load(nbt.getCompoundTag(Settings.namespace + "node"))
    }
  }

  override def writeToNBT(nbt: NBTTagCompound) {
    super.writeToNBT(nbt)
    if (node != null && node.host == this) {
      nbt.setNewCompoundTag(Settings.namespace + "node", node.save)
    }
  }

  // ----------------------------------------------------------------------- //

  override def onMessage(message: network.Message) {}

  override def onConnect(node: network.Node) {}

  override def onDisconnect(node: network.Node) {
    if (node == this.node) node match {
      case connector: Connector => connector.setLocalBufferSize(0)
      case _ =>
    }
  }

  // ----------------------------------------------------------------------- //

  protected def result(args: Any*) = li.cil.oc.util.ResultWrapper.result(args: _*)
}
