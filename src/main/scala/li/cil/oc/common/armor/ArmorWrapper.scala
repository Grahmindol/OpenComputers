package li.cil.oc.common.armor

import li.cil.oc._
import li.cil.oc.api.{Driver, Network}
import li.cil.oc.api.driver.item.Container
import li.cil.oc.api.network.{EnvironmentHost, Message, Node, Visibility}
import li.cil.oc.common.armor.{Armor => ArmorComponent}
import li.cil.oc.common.container.ComponentInventory
import li.cil.oc.common.{ItemMachineWrapper, Slot, Tier}
import li.cil.oc.integration.opencomputers.DriverScreen
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

class ArmorWrapper(player: Player) extends ItemMachineWrapper(player.getItemBySlot(EquipmentSlot.CHEST), player) {

  val data = new ArmorData()
  var checksum: String = ""

  val internalComponent: ArmorComponent = if (getEnvironmentLevel.isClientSide) null else new ArmorComponent(this)

  // ----------------------------------------------------------------------- //

  readFromNBT(player.registryAccess())

  if (!getEnvironmentLevel.isClientSide) {
    api.Network.joinNewNetwork(machine.node)

    //val charge = Math.max(0, data.energy - internalComponent.node.globalBuffer)
    internalComponent.node.changeBuffer(Double.PositiveInfinity)

    writeToNBT(player.registryAccess())
  }

  // ----------------------------------------------------------------------- //

  override def isCreative: Boolean = true

  override def host: ArmorWrapper = this

  // ----------------------------------------------------------------------- //

  override def canPlaceItem(slot: Int, stack: ItemStack): Boolean =
    slot == getContainerSize - 1 &&
      (Option(Driver.driverFor(stack, getClass)) match {
        case Some(driver) =>
          // Same special cases, similar as in robot, but allow keyboards,
          // because clip-on keyboards kinda seem to make sense, I guess.
          driver != DriverScreen &&
            driver.slot(stack) == containerSlotType &&
            driver.tier(stack) <= containerSlotTier

        case _ =>
          false
      })

  // ----------------------------------------------------------------------- //

  override def onClientInit(level: Level, player: Player): Unit = {
    super.onClientInit(level, player)
    componentSlots collect {
      case Some(buffer: api.internal.TextBuffer) =>
        buffer.setMaximumColorDepth(api.internal.TextBuffer.ColorDepth.FourBit)
        buffer.setMaximumResolution(80, 25)
    }
  }

}

class ArmorPieceWrapper(var stack: ItemStack, host: ArmorWrapper ) extends ComponentInventory {
  val data = new ArmorData()

  override def host: EnvironmentHost = host

  override def setChanged(): Unit = {
    saveData(stack)
    host.setChanged()
  }

  // TODO : fix it
  override def stillValid(player: Player): Boolean = true

  override def node(): Node = Network.newNode(this, Visibility.Network).withConnector(Settings.get.bufferTablet).create()


  override def onConnect(node: Node): Unit = {
    if (node == this.node) {
      connectComponents()
    }
  }

  override def onDisconnect(node: Node): Unit = {
    if (node == this.node) {
      disconnectComponents()
    }
  }

  override def onMessage(message: Message): Unit = {}

  override def items: Array[ItemStack] = data.items

  override def getContainerSize: Int = items.length
}
