package li.cil.oc.common.armor

import li.cil.oc.api.network.EnvironmentHost
import li.cil.oc.api.{ImmutableItemStack, internal}
import li.cil.oc.common.datacomponents.OCComponents
import li.cil.oc.common.item.data.TabletData
import li.cil.oc.common.template.Template
import li.cil.oc.common.{Slot, Tier}
import li.cil.oc.{Constants, Localization, Settings, api}
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.armortrim.{ArmorTrim, TrimMaterials, TrimPatterns}
import net.neoforged.neoforge.server.ServerLifecycleHooks

import scala.annotation.unused
import scala.collection.mutable
import scala.jdk.CollectionConverters.IterableHasAsJava

object ArmorTemplate extends Template {
  override protected val suggestedComponents = Array(
    "BIOS" -> hasComponent(Constants.ItemName.EEPROM),
    "Keyboard" -> hasComponent(Constants.BlockName.Keyboard),
    "GraphicsCard" -> ((inventory: Container) => Array(
      Constants.ItemName.APUCreative,
      Constants.ItemName.APUTier1,
      Constants.ItemName.APUTier2,
      Constants.ItemName.GraphicsCardTier1,
      Constants.ItemName.GraphicsCardTier2,
      Constants.ItemName.GraphicsCardTier3).
      exists(name => hasComponent(name)(inventory))),
    "OS" -> hasFileSystem)

  override protected def hostClass: Class[_ <: EnvironmentHost] = classOf[internal.Robot]

  @unused
  def selectHelmet(stack: ItemStack): Boolean = stack.is(net.minecraft.world.item.Items.NETHERITE_HELMET)
  @unused
  def selectChestplate(stack: ItemStack): Boolean = stack.is(net.minecraft.world.item.Items.NETHERITE_CHESTPLATE)
  @unused
  def selectLeggings(stack: ItemStack): Boolean = stack.is(net.minecraft.world.item.Items.NETHERITE_LEGGINGS)
  @unused
  def selectBoots(stack: ItemStack): Boolean = stack.is(net.minecraft.world.item.Items.NETHERITE_BOOTS)


  private def hasTrim(inventory: Container) = exists(inventory, api.Driver.driverFor(_, hostClass) match {
    case DriverTrim => true
    case _ => false
  })

  def validateChest(inventory: Container): Array[AnyRef] = {
    val hasTrim = this.hasTrim(inventory)
    val hasCPU = this.hasCPU(inventory)
    val hasRAM = this.hasRAM(inventory)
    val complexity = this.complexity(inventory)
    val maxComplexity = this.maxComplexity(inventory)

    val valid = hasTrim && hasCPU && hasRAM && complexity <= maxComplexity

    val progress =
      if (!hasCPU) Localization.Assembler.InsertCPU
      else if (!hasRAM) Localization.Assembler.InsertRAM
      else if (!hasTrim) Localization.Assembler.InsertTrim
      else Localization.Assembler.Complexity(complexity, maxComplexity)


    val warnings = mutable.ArrayBuffer.empty[Component]
    for ((name, check) <- suggestedComponents) {
      if (!check(inventory)) {
        warnings += Localization.Assembler.Warning(name)
      }
    }
    if (warnings.nonEmpty) {
      warnings.prepend(Localization.Assembler.Warnings)
    }

    Array(valid: java.lang.Boolean, progress, warnings.toArray)
  }

  def validate(inventory: Container): Array[AnyRef] = {
    val hasTrim = this.hasTrim(inventory)
    val complexity = this.complexity(inventory)
    val maxComplexity = this.maxComplexity(inventory)

    val valid = hasTrim && complexity <= maxComplexity

    val progress =
      if (!hasTrim) Localization.Assembler.InsertTrim
      else Localization.Assembler.Complexity(complexity, maxComplexity)

    Array(valid: java.lang.Boolean, progress, mutable.ArrayBuffer.empty[Component])
  }

  def assemble(inventory: Container): Array[AnyRef] = {
    val registries = ServerLifecycleHooks.getCurrentServer.registryAccess()
    val items = (1 until inventory.getContainerSize).map(slot => inventory.getItem(slot))
    val stack = inventory.getItem(0)

    stack.set(OCComponents.CONTENTS, items.map(ImmutableItemStack.copyOf).toList)

    val optional = TrimPatterns.getFromTemplate(registries, inventory.getItem(13))
    val optional1 = registries.lookupOrThrow(Registries.TRIM_MATERIAL).get(TrimMaterials.REDSTONE)
    if (optional.isPresent && optional1.isPresent) stack.set(DataComponents.TRIM, new ArmorTrim(optional1.get, optional.get))

    stack.set(DataComponents.CUSTOM_NAME, Component.literal("Upgraded Trim"))
    val energy = Settings.get.tabletBaseCost + complexity(inventory) * Settings.get.tabletComplexityCost

    Array(stack, Double.box(energy))
  }

  @unused
  def selectDisassembler(stack: ItemStack): Boolean = api.Items.get(stack) == api.Items.get(Constants.ItemName.Tablet)

  def disassemble(stack: ItemStack, @unused ingredients: Array[ItemStack]): Array[ItemStack] = {
    val info = new TabletData(stack)
    val itemName = Constants.ItemName.TabletCase(info.tier)
    (Array(api.Items.get(itemName).createItemStack(1), info.container) ++ info.items.filter(!_.isEmpty).drop(1) /* Screen */).filter(!_.isEmpty)
  }

  def register(): Unit = {
    // Helmet
    api.IMC.registerAssemblerTemplate(
      "Helmet Upgrade",
      "li.cil.oc.common.armor.ArmorTemplate.selectHelmet",
      "li.cil.oc.common.armor.ArmorTemplate.validate",
      "li.cil.oc.common.armor.ArmorTemplate.assemble",
      hostClass,
      null,Array(
        Tier.Three,
        Tier.Two,
        Tier.One
      ),
      Iterable(
        (Slot.Trim, Tier.Any),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
      ).map(toPair).asJava
    )

    api.IMC.registerAssemblerTemplate(
      "Chestplate Upgrade",
      "li.cil.oc.common.armor.ArmorTemplate.selectChestplate",
      "li.cil.oc.common.armor.ArmorTemplate.validateChest",
      "li.cil.oc.common.armor.ArmorTemplate.assemble",
      hostClass,
      Array(
        Tier.Three
      ),
      Array(
        Tier.Three,
        Tier.Two,
        Tier.One
      ),
      Iterable(
        (Slot.Trim, Tier.Any),
        (Slot.Card, Tier.Two),
        null,
        (Slot.CPU, Tier.Two),
        (Slot.Memory, Tier.Two),
        (Slot.Memory, Tier.Two),
        (Slot.EEPROM, Tier.Any),
        (Slot.HDD, Tier.Two)
      ).map(toPair).asJava
    )

    api.IMC.registerAssemblerTemplate(
      "Leggings Upgrade",
      "li.cil.oc.common.armor.ArmorTemplate.selectLeggings",
      "li.cil.oc.common.armor.ArmorTemplate.validate",
      "li.cil.oc.common.armor.ArmorTemplate.assemble",
      hostClass,
      null,Array(
        Tier.Three,
        Tier.Two,
        Tier.One
      ),
      Iterable(
        (Slot.Trim, Tier.Any),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
      ).map(toPair).asJava
    )

    api.IMC.registerAssemblerTemplate(
      "Boots Upgrade",
      "li.cil.oc.common.armor.ArmorTemplate.selectBoots",
      "li.cil.oc.common.armor.ArmorTemplate.validate",
      "li.cil.oc.common.armor.ArmorTemplate.assemble",
      hostClass,
      null,Array(
        Tier.Three,
        Tier.Two,
        Tier.One
      ),
      Iterable(
        (Slot.Trim, Tier.Any),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
      ).map(toPair).asJava
    )

  }

  override protected def maxComplexity(inventory: Container): Int = super.maxComplexity(inventory) / 2 + 5

  // max complexity !!!!
  override protected def caseTier(inventory: Container): Int = Tier.Four
}

