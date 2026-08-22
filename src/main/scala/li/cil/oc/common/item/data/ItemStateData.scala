package li.cil.oc.common.item.data

import li.cil.oc.api.{ImmutableItemStack, Persistable}
import li.cil.oc.common.datacomponents.OCComponents
import li.cil.oc.util.ExtendedDataComponentHolder.convert
import net.minecraft.core.component.DataComponentHolder
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.common.MutableDataComponentHolder

abstract class ItemStateData extends Persistable {
  def this(stack: ItemStack) = {
    this()
    loadData(stack)
  }

  var items: Array[ItemStack] = Array.fill[ItemStack](32)(ItemStack.EMPTY)
  var isRunning = false
  var energy = 0.0
  var maxEnergy = 0.0
  var container: ItemStack = ItemStack.EMPTY

  def loadData(holder: DataComponentHolder): Unit = {
    for(contents <- holder.getComponent(OCComponents.CONTENTS)) {
      for(itemStack -> i <- contents.take(items.length).zipWithIndex) {
        items(i) = itemStack.mutableCopy()
      }
    }
    isRunning = holder.getComponent(OCComponents.IS_RUNNING) getOrElse false
    energy = holder.getComponent(OCComponents.CHARGE) getOrElse 0
    maxEnergy = holder.getComponent(OCComponents.MAX_CHARGE) getOrElse 0
    container = (holder.getComponent(OCComponents.ATTACHMENT) getOrElse ImmutableItemStack.EMPTY).mutableCopy()
  }

  def saveData(holder: MutableDataComponentHolder): Unit = {
    holder.setComponent(OCComponents.CONTENTS, items.map(ImmutableItemStack.copyOf).toList)
    holder.setComponent(OCComponents.IS_RUNNING, isRunning)
    holder.setComponent(OCComponents.CHARGE, energy)
    holder.setComponent(OCComponents.MAX_CHARGE, maxEnergy)
    holder.setComponent(OCComponents.ATTACHMENT, Option.when(!container.isEmpty) { ImmutableItemStack.copyOf(container) })
  }
}
