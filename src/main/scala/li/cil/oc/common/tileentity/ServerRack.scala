package li.cil.oc.common.tileentity

import com.google.common.base.Strings
import li.cil.oc._
import li.cil.oc.api.Network
import li.cil.oc.api.internal
import li.cil.oc.api.network.Analyzable
import li.cil.oc.api.network._
import li.cil.oc.client.Sound
import li.cil.oc.common.Tier
import li.cil.oc.integration.opencomputers.DriverRedstoneCard
import li.cil.oc.server.component
import li.cil.oc.server.{PacketSender => ServerPacketSender}
import li.cil.oc.util.ExtendedNBT._
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.nbt.NBTTagString
import net.minecraft.util.EnumFacing
import net.minecraftforge.common.util.Constants.NBT
import net.minecraftforge.event.world.WorldEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly

import scala.collection.mutable

class ServerRack extends traits.PowerAcceptor with traits.Hub with traits.PowerBalancer with traits.Inventory with traits.Rotatable with traits.BundledRedstoneAware with Analyzable with internal.ServerRack {
  val servers = Array.fill(getSizeInventory)(None: Option[component.Server])

  val sides = Seq(Option(EnumFacing.UP), Option(EnumFacing.EAST), Option(EnumFacing.WEST), Option(EnumFacing.DOWN)).
    padTo(servers.length, None).toArray

  val terminals = (0 until servers.length).map(new common.component.Terminal(this, _)).toArray

  var range = 16

  // For client side, where we don't create the component.
  private val _isRunning = new Array[Boolean](getSizeInventory)

  private var markChunkDirty = false

  var internalSwitch = false

  // For client side rendering.
  var isPresent = Array.fill[Option[String]](getSizeInventory)(None)

  // Used on client side to check whether to render disk activity indicators.
  var lastAccess = Array.fill(4)(0L)

  override def server(slot: Int) = servers(slot).orNull

  @SideOnly(Side.CLIENT)
  override protected def hasConnector(side: EnumFacing) = side != facing

  override protected def connector(side: EnumFacing) = Option(if (side != facing) sidedNode(side).asInstanceOf[Connector] else null)

  override protected def energyThroughput = Settings.get.serverRackRate

  // ----------------------------------------------------------------------- //

  override def canConnect(side: EnumFacing) = side != facing

  // ----------------------------------------------------------------------- //

  def isRunning(number: Int) =
    if (isServer) servers(number).fold(false)(_.machine.isRunning)
    else _isRunning(number)

  @SideOnly(Side.CLIENT)
  def setRunning(number: Int, value: Boolean) = {
    _isRunning(number) = value
    world.markBlockForUpdate(getPos)
    if (anyRunning) Sound.startLoop(this, "computer_running", 1.5f, 50 + world.rand.nextInt(50))
    else Sound.stopLoop(this)
    this
  }

  def anyRunning = (0 until servers.length).exists(isRunning)

  // ----------------------------------------------------------------------- //

  def markForSaving() = markChunkDirty = true

  def hasRedstoneCard = servers exists {
    case Some(server) => server.machine.isRunning && server.inventory.items.exists {
      case Some(stack) => DriverRedstoneCard.worksWith(stack, server.getClass)
      case _ => false
    }
    case _ => false
  }

  def reconnectServer(number: Int, server: component.Server) {
    sides(number) match {
      case Some(serverSide) =>
        val serverNode = server.machine.node
        for (side <- EnumFacing.values) {
          if (toLocal(side) == serverSide) sidedNode(side).connect(serverNode)
          else sidedNode(side).disconnect(serverNode)
        }
      case _ =>
    }
  }

  // ----------------------------------------------------------------------- //

  override protected def distribute() = {
    def node(side: Int) = sides(side) match {
      case None => servers(side).fold(null: Connector)(_.machine.node.asInstanceOf[Connector])
      case _ => null
    }
    val nodes = (0 to 3).map(node)
    def network(connector: Connector) = if (connector != null && connector.network != null) connector.network else this
    val (sumBuffer, sumSize) = super.distribute()
    var sumBufferServers, sumSizeServers = 0.0
    network(nodes(0)).synchronized {
      network(nodes(1)).synchronized {
        network(nodes(2)).synchronized {
          network(nodes(3)).synchronized {
            for (node <- nodes if node != null) {
              sumBufferServers += node.globalBuffer
              sumSizeServers += node.globalBufferSize
            }
            if (sumSize + sumSizeServers > 0) {
              val ratio = (sumBuffer + sumBufferServers) / (sumSize + sumSizeServers)
              for (node <- nodes if node != null) {
                node.changeBuffer(node.globalBufferSize * ratio - node.globalBuffer)
              }
            }
          }
        }
      }
    }
    (sumBuffer + sumBufferServers, sumSize + sumSizeServers)
  }

  // ----------------------------------------------------------------------- //

  override protected def relayPacket(sourceSide: Option[EnumFacing], packet: Packet) {
    if (internalSwitch) {
      for (slot <- 0 until servers.length) {
        val side = sides(slot).map(toGlobal)
        if (side != sourceSide) {
          servers(slot) match {
            case Some(server) => server.machine.node.sendToNeighbors("network.message", packet)
            case _ =>
          }
        }
      }
    }
    else super.relayPacket(sourceSide, packet)
  }

  override protected def onPlugMessage(plug: Plug, message: Message) {
    // This check is a little hacky. Basically what we test here is whether
    // the message was relayed internally, because only internally relayed
    // network messages originate from the actual server nodes themselves.
    // The otherwise come from the network card.
    if (message.name != "network.message" || !(servers collect {
      case Some(server) => server.machine.node
    }).contains(message.source)) super.onPlugMessage(plug, message)
  }

  // ----------------------------------------------------------------------- //

  override def getSizeInventory = 4

  override def getInventoryStackLimit = 1

  override def isItemValidForSlot(i: Int, stack: ItemStack) = {
    val descriptor = api.Items.get(stack)
    descriptor == api.Items.get("server1") ||
      descriptor == api.Items.get("server2") ||
      descriptor == api.Items.get("server3") ||
      descriptor == api.Items.get("serverCreative")
  }

  // ----------------------------------------------------------------------- //

  override def onAnalyze(player: EntityPlayer, side: EnumFacing, hitX: Float, hitY: Float, hitZ: Float) = {
    slotAt(side, hitX, hitY, hitZ) match {
      case Some(slot) => servers(slot) match {
        case Some(server) =>
          val computer = server.machine
          computer.lastError match {
            case value if value != null =>
              player.addChatMessage(Localization.Analyzer.LastError(value))
            case _ =>
          }
          player.addChatMessage(Localization.Analyzer.Components(computer.componentCount, servers(slot).get.maxComponents))
          val list = computer.users
          if (list.size > 0) {
            player.addChatMessage(Localization.Analyzer.Users(list))
          }
          Array(computer.node)
        case _ => null
      }
      case _ => Array(sidedNode(side))
    }
  }

  def slotAt(side: EnumFacing, hitX: Float, hitY: Float, hitZ: Float) = {
    if (side == facing) {
      val l = 2 / 16.0
      val h = 14 / 16.0
      val slot = (((1 - hitY) - l) / (h - l) * 4).toInt
      Some(math.max(0, math.min(servers.length, slot)))
    }
    else None
  }

  // ----------------------------------------------------------------------- //

  override def canUpdate = isServer

  override def updateEntity() {
    super.updateEntity()
    if (isServer && isConnected) {
      val shouldUpdatePower = world.getTotalWorldTime % Settings.get.tickFrequency == 0
      if (shouldUpdatePower && range > 0 && !Settings.get.ignorePower) {
        val countRunning = servers.count {
          case Some(server) => server.machine.isRunning
          case _ => false
        }
        if (countRunning > 0) {
          var cost = -(countRunning * range * Settings.get.wirelessCostPerRange * Settings.get.tickFrequency)
          for (side <- EnumFacing.values if cost < 0) {
            sidedNode(side) match {
              case connector: Connector => cost = connector.changeBuffer(cost)
              case _ =>
            }
          }
        }
      }

      servers collect {
        case Some(server) =>
          if (shouldUpdatePower && server.tier == Tier.Four) {
            server.machine.node.asInstanceOf[Connector].changeBuffer(Double.PositiveInfinity)
          }
          server.machine.update()
      }

      if (markChunkDirty) {
        markChunkDirty = false
        world.markChunkDirty(getPos, this)
      }

      for (i <- 0 until servers.length) {
        val isRunning = servers(i).fold(false)(_.machine.isRunning)
        if (_isRunning(i) != isRunning) {
          _isRunning(i) = isRunning
          ServerPacketSender.sendServerState(this, i)
        }
      }
      isOutputEnabled = hasRedstoneCard

      servers collect {
        case Some(server) =>
          server.inventory.updateComponents()
          terminals(server.slot).buffer.update()
      }
    }
  }

  // ----------------------------------------------------------------------- //

  override protected def initialize() {
    super.initialize()
    if (isClient) {
      ServerRack.list += this -> Unit
    }
  }

  override def dispose() {
    super.dispose()
    if (isClient) {
      ServerRack.list -= this
    }
  }

  override def readFromNBT(nbt: NBTTagCompound) {
    super.readFromNBT(nbt)
    for (slot <- 0 until getSizeInventory) {
      if (getStackInSlot(slot) != null) {
        val server = new component.Server(this, slot)
        servers(slot) = Option(server)
      }
    }
    nbt.getTagList(Settings.namespace + "servers", NBT.TAG_COMPOUND).toArray[NBTTagCompound].
      zipWithIndex.foreach {
      case (tag, index) if index < servers.length =>
        servers(index) match {
          case Some(server) =>
            try server.load(tag) catch {
              case t: Throwable => OpenComputers.log.warn("Failed restoring server state. Please report this!", t)
            }
          case _ =>
        }
      case _ =>
    }
    val sidesNbt = nbt.getByteArray(Settings.namespace + "sides").map {
      case side if side >= 0 => Option(EnumFacing.getFront(side))
      case _ => None
    }
    Array.copy(sidesNbt, 0, sides, 0, math.min(sidesNbt.length, sides.length))
    nbt.getTagList(Settings.namespace + "terminals", NBT.TAG_COMPOUND).toArray[NBTTagCompound].
      zipWithIndex.foreach {
      case (tag, index) if index < terminals.length =>
        try terminals(index).load(tag) catch {
          case t: Throwable => OpenComputers.log.warn("Failed restoring terminal state. Please report this!", t)
        }
      case _ =>
    }
    range = nbt.getInteger(Settings.namespace + "range")
    internalSwitch = nbt.getBoolean(Settings.namespace + "internalSwitch")
  }

  override def writeToNBT(nbt: NBTTagCompound) = if (isServer) {
    nbt.setNewTagList(Settings.namespace + "servers", servers map {
      case Some(server) =>
        val serverNbt = new NBTTagCompound()
        try server.save(serverNbt) catch {
          case t: Throwable => OpenComputers.log.warn("Failed saving server state. Please report this!", t)
        }
        serverNbt
      case _ => new NBTTagCompound()
    })
    super.writeToNBT(nbt)
    nbt.setByteArray(Settings.namespace + "sides", sides.map {
      case Some(side) => side.ordinal.toByte
      case _ => -1: Byte
    })
    nbt.setNewTagList(Settings.namespace + "terminals", terminals.map(t => {
      val terminalNbt = new NBTTagCompound()
      try t.save(terminalNbt) catch {
        case t: Throwable => OpenComputers.log.warn("Failed saving terminal state. Please report this!", t)
      }
      terminalNbt
    }))
    nbt.setInteger(Settings.namespace + "range", range)
    nbt.setBoolean(Settings.namespace + "internalSwitch", internalSwitch)
  }

  @SideOnly(Side.CLIENT)
  override def readFromNBTForClient(nbt: NBTTagCompound) {
    super.readFromNBTForClient(nbt)
    val isRunningNbt = nbt.getByteArray("isServerRunning").map(_ == 1)
    Array.copy(isRunningNbt, 0, _isRunning, 0, math.min(isRunningNbt.length, _isRunning.length))
    val isPresentNbt = nbt.getTagList("isPresent", NBT.TAG_STRING).map((tag: NBTTagString) => {
      val value = tag.getString()
      if (Strings.isNullOrEmpty(value)) None else Some(value)
    }).toArray
    Array.copy(isPresentNbt, 0, isPresent, 0, math.min(isPresentNbt.length, isPresent.length))
    val sidesNbt = nbt.getByteArray("sides").map {
      case side if side >= 0 => Option(EnumFacing.getFront(side))
      case _ => None
    }
    Array.copy(sidesNbt, 0, sides, 0, math.min(sidesNbt.length, sides.length))
    nbt.getTagList("terminals", NBT.TAG_COMPOUND).toArray[NBTTagCompound].
      zipWithIndex.foreach {
      case (tag, index) if index < terminals.length => terminals(index).readFromNBTForClient(tag)
      case _ =>
    }
    range = nbt.getInteger("range")
    if (anyRunning) Sound.startLoop(this, "computer_running", 1.5f, 1000 + world.rand.nextInt(2000))
  }

  override def writeToNBTForClient(nbt: NBTTagCompound) {
    super.writeToNBTForClient(nbt)
    nbt.setByteArray("isServerRunning", _isRunning.map(value => (if (value) 1 else 0).toByte))
    nbt.setNewTagList("isPresent", servers.map(value => new NBTTagString(value.fold("")(_.machine.node.address))))
    nbt.setByteArray("sides", sides.map {
      case Some(side) => side.ordinal.toByte
      case _ => -1: Byte
    })
    nbt.setNewTagList("terminals", terminals.map(t => {
      val terminalNbt = new NBTTagCompound()
      t.writeToNBTForClient(terminalNbt)
      terminalNbt
    }))
    nbt.setInteger("range", range)
  }

  // ----------------------------------------------------------------------- //

  override protected def onPlugConnect(plug: Plug, node: Node) {
    if (node == plug.node) {
      for (number <- 0 until servers.length) {
        val serverSide = sides(number).map(toGlobal)
        servers(number) match {
          case Some(server) =>
            if (serverSide == Option(plug.side)) plug.node.connect(server.machine.node)
            else api.Network.joinNewNetwork(server.machine.node)
            terminals(number).connect(server.machine.node)
          case _ =>
        }
      }
    }
  }

  override protected def createNode(plug: Plug) = api.Network.newNode(plug, Visibility.Network).
    withConnector(Settings.get.bufferDistributor).
    create()

  override protected def onItemAdded(slot: Int, stack: ItemStack) {
    super.onItemAdded(slot, stack)
    if (isServer) {
      val server = new component.Server(this, slot)
      servers(slot) = Some(server)
      reconnectServer(slot, server)
      Network.joinNewNetwork(server.machine.node)
      terminals(slot).connect(server.machine.node)
    }
  }

  override protected def onItemRemoved(slot: Int, stack: ItemStack) {
    super.onItemRemoved(slot, stack)
    if (isServer) {
      servers(slot) match {
        case Some(server) =>
          server.machine.node.remove()
          server.inventory.containerOverride = stack
          server.inventory.save(new NBTTagCompound()) // Only flush components.
          server.inventory.markDirty()
        case _ =>
      }
      servers(slot) = None
      terminals(slot).keys.clear()
    }
  }

  override def markDirty() {
    super.markDirty()
    if (isServer) {
      isOutputEnabled = hasRedstoneCard
      ServerPacketSender.sendServerPresence(this)
    }
    else {
      world.markBlockForUpdate(getPos)
    }
  }

  override protected def onRotationChanged() {
    super.onRotationChanged()
    checkRedstoneInputChanged()
  }

  override protected def onRedstoneInputChanged(side: EnumFacing) {
    super.onRedstoneInputChanged(side)
    servers collect {
      case Some(server) => server.machine.signal("redstone_changed", server.machine.node.address, Int.box(toLocal(side).ordinal()))
    }
  }

  override def rotate(axis: EnumFacing) = false
}

object ServerRack {
  val list = mutable.WeakHashMap.empty[ServerRack, Unit]

  @SubscribeEvent
  def onWorldUnload(e: WorldEvent.Unload) {
    if (e.world.isRemote) {
      list.clear()
    }
  }
}
