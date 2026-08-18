package li.cil.oc.common.armor

import li.cil.oc.api.ImmutableItemStack
import li.cil.oc.common.datacomponents.OCComponents
import li.cil.oc.util.ExtendedDataComponentHolder._
import net.minecraft.world.entity.{EquipmentSlot, LivingEntity}
import net.minecraft.world.item.ItemStack

import scala.collection.mutable.ArrayBuffer

class ArmorData {
  var items: Array[ItemStack] = Array.fill[ItemStack](32)(ItemStack.EMPTY)
  private var itemOrigins: Array[Option[EquipmentSlot]] = Array.fill(32)(None)

  var isRunning: Boolean = false
  var energy: Double = 0.0
  var maxEnergy: Double = 0.0
  var container: ItemStack = ItemStack.EMPTY

  def this(player: LivingEntity) = {
    this()
    loadData(player)
  }

  def loadData(player: LivingEntity): Unit = {
    val buffer = ArrayBuffer[ItemStack]()
    val origins = ArrayBuffer[EquipmentSlot]()

    isRunning = false
    energy = 0.0
    maxEnergy = 0.0
    container = ItemStack.EMPTY

    def loadSlot(slot: EquipmentSlot): Unit = {
      val stack = player.getItemBySlot(slot)
      if (!stack.isEmpty) {
        for (contents <- stack.getComponent(OCComponents.CONTENTS)) {
          for (itemStack <- contents) {
            buffer += itemStack.mutableCopy()
            origins += slot
          }
        }
        energy += stack.getComponent(OCComponents.CHARGE).getOrElse(0.0)
        maxEnergy += stack.getComponent(OCComponents.MAX_CHARGE).getOrElse(0.0)
      }
    }

    loadSlot(EquipmentSlot.HEAD)
    loadSlot(EquipmentSlot.CHEST)
    loadSlot(EquipmentSlot.LEGS)
    loadSlot(EquipmentSlot.FEET)

    // Store mapped items and their slot origins
    val clampedCount = math.min(buffer.length, 32)
    items = buffer.take(clampedCount).padTo(32, ItemStack.EMPTY).toArray
    itemOrigins = origins.take(clampedCount).map(Some(_)).padTo(32, None).toArray

    val chestStack = player.getItemBySlot(EquipmentSlot.CHEST)
    if (!chestStack.isEmpty) {
      container = chestStack.getComponent(OCComponents.ATTACHMENT).map(_.mutableCopy()).getOrElse(ItemStack.EMPTY)
      isRunning = chestStack.getComponent(OCComponents.IS_RUNNING).getOrElse(false)
    }
  }

  def saveData(player: LivingEntity): Unit = {
    val slotContents = Map(
      EquipmentSlot.HEAD -> ArrayBuffer[ImmutableItemStack](),
      EquipmentSlot.CHEST -> ArrayBuffer[ImmutableItemStack](),
      EquipmentSlot.LEGS -> ArrayBuffer[ImmutableItemStack](),
      EquipmentSlot.FEET -> ArrayBuffer[ImmutableItemStack]()
    )

    for (i <- items.indices) {
      val stack = items(i)
      itemOrigins(i).foreach { slot =>
        if (!stack.isEmpty) {
          slotContents(slot) += ImmutableItemStack.copyOf(stack)
        }
      }
    }

    val chestStack = player.getItemBySlot(EquipmentSlot.CHEST)
    if (!chestStack.isEmpty && chestStack.has(OCComponents.CONTENTS)) {
      val attachmentOpt = Option.when(!container.isEmpty)(ImmutableItemStack.copyOf(container))
      chestStack.setComponent(OCComponents.ATTACHMENT, attachmentOpt)
    }

    def saveSlot(slot: EquipmentSlot): Unit = {
      val stack = player.getItemBySlot(slot)
      if (!stack.isEmpty && stack.has(OCComponents.CONTENTS)) {
        stack.setComponent(OCComponents.CONTENTS, slotContents(slot).toList)
        stack.setComponent(OCComponents.IS_RUNNING, isRunning)
        stack.setComponent(OCComponents.CHARGE, energy / 4.0)
        stack.setComponent(OCComponents.MAX_CHARGE, maxEnergy / 4.0)
      }
    }

    saveSlot(EquipmentSlot.HEAD)
    saveSlot(EquipmentSlot.CHEST)
    saveSlot(EquipmentSlot.LEGS)
    saveSlot(EquipmentSlot.FEET)
  }
}
