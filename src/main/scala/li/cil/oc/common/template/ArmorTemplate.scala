package li.cil.oc.common.template

import li.cil.oc.{Constants, Localization, Settings, api}
import li.cil.oc.api.{ImmutableItemStack, internal}
import li.cil.oc.common.datacomponents.OCComponents
import li.cil.oc.common.{Slot, Tier}
import li.cil.oc.common.item.data.TabletData
import li.cil.oc.common.template.TabletTemplate.{complexity, hasComponent, hasFileSystem, toPair, validateComputer}
import li.cil.oc.util.ItemUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.atlas.sources.PalettedPermutations
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.tags.ItemTags
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.armortrim.{ArmorTrim, TrimMaterial, TrimMaterials, TrimPattern, TrimPatterns}
import net.neoforged.neoforge.server.ServerLifecycleHooks

import java.util.Optional
import scala.collection.JavaConverters.asJavaIterable
import scala.collection.mutable
import scala.jdk.OptionConverters.RichOptional

object ArmorTemplate extends Template {
  override protected val suggestedComponents = Array(
    "BIOS" -> hasComponent(Constants.ItemName.EEPROM) _,
    "Keyboard" -> hasComponent(Constants.BlockName.Keyboard) _,
    "GraphicsCard" -> ((inventory: Container) => Array(
      Constants.ItemName.APUCreative,
      Constants.ItemName.APUTier1,
      Constants.ItemName.APUTier2,
      Constants.ItemName.GraphicsCardTier1,
      Constants.ItemName.GraphicsCardTier2,
      Constants.ItemName.GraphicsCardTier3).
      exists(name => hasComponent(name)(inventory))),
    "OS" -> hasFileSystem _)

  override protected def hostClass = classOf[internal.Tablet]

  def selectHelmet(stack: ItemStack) = stack.is(net.minecraft.world.item.Items.NETHERITE_HELMET)
  def selectChestplate(stack: ItemStack) = stack.is(net.minecraft.world.item.Items.NETHERITE_CHESTPLATE)
  def selectLeggings(stack: ItemStack) = stack.is(net.minecraft.world.item.Items.NETHERITE_LEGGINGS)
  def selectBoots(stack: ItemStack) = stack.is(net.minecraft.world.item.Items.NETHERITE_BOOTS)


  protected def hasTrim(inventory: Container) = exists(inventory, api.Driver.driverFor(_, hostClass) match {
    case li.cil.oc.integration.opencomputers.DriverTrim => true
    case _ => false
  })

  def validate(inventory: Container): Array[AnyRef] = {
    val hasTrim = this.hasTrim(inventory)
    val hasCPU = this.hasCPU(inventory)
    val hasRAM = this.hasRAM(inventory)
    val requiresRAM = this.requiresRAM(inventory)
    val complexity = this.complexity(inventory)
    val maxComplexity = this.maxComplexity(inventory)

    val valid = hasTrim //&& hasCPU && (hasRAM || !requiresRAM) && complexity <= maxComplexity

    val progress =
      if (!hasCPU) Localization.Assembler.InsertCPU
      else if (!hasRAM && requiresRAM) Localization.Assembler.InsertRAM
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

  def assemble(inventory: Container): Array[AnyRef] = {
    val registries = ServerLifecycleHooks.getCurrentServer.registryAccess()
    val items = (1 until inventory.getContainerSize).map(slot => inventory.getItem(slot))
    val stack = inventory.getItem(0)

    stack.set(OCComponents.CONTENTS, items.map(ImmutableItemStack.copyOf).toList)

    //PalettedPermutations
    val optional = TrimPatterns.getFromTemplate(registries, inventory.getItem(13));
    val optional1 = registries.lookupOrThrow(Registries.TRIM_MATERIAL).get(TrimMaterials.REDSTONE)
    if (optional.isPresent && optional1.isPresent) stack.set(DataComponents.TRIM, new ArmorTrim(optional1.get, optional.get))

    stack.set(DataComponents.CUSTOM_NAME, Component.literal("Upgraded Trim"))
    val energy = Settings.get.tabletBaseCost + complexity(inventory) * Settings.get.tabletComplexityCost

    Array(stack, Double.box(energy))
  }

  def selectDisassembler(stack: ItemStack) = api.Items.get(stack) == api.Items.get(Constants.ItemName.Tablet)

  def disassemble(stack: ItemStack, ingredients: Array[ItemStack]) = {
    val info = new TabletData(stack)
    val itemName = Constants.ItemName.TabletCase(info.tier)
    (Array(api.Items.get(itemName).createItemStack(1), info.container) ++ info.items.filter(!_.isEmpty).drop(1) /* Screen */).filter(!_.isEmpty)
  }

  def register(): Unit = {
    // Helmet
    api.IMC.registerAssemblerTemplate(
      "Helmet Upgrade",
      "li.cil.oc.common.template.ArmorTemplate.selectHelmet",
      "li.cil.oc.common.template.ArmorTemplate.validate",
      "li.cil.oc.common.template.ArmorTemplate.assemble",
      hostClass,
      null,Array(
        Tier.Three,
        Tier.Two,
        Tier.One
      ),
      asJavaIterable(Iterable(
        (Slot.Trim, Tier.Any),
        null,
        null,
        (Slot.CPU, Tier.Two),
        null,
        null,
        null,
        null,
      ).map(toPair)))

    api.IMC.registerAssemblerTemplate(
      "Chestplate Upgrade",
      "li.cil.oc.common.template.ArmorTemplate.selectChestplate",
      "li.cil.oc.common.template.ArmorTemplate.validate",
      "li.cil.oc.common.template.ArmorTemplate.assemble",
      hostClass,
      null,
      Array(
        Tier.Three,
        Tier.Two,
        Tier.One
      ),
      asJavaIterable(Iterable(
        (Slot.Trim, Tier.Any),
        (Slot.Card, Tier.Two),
        null,
        (Slot.CPU, Tier.Two),
        (Slot.Memory, Tier.Two),
        (Slot.Memory, Tier.Two),
        (Slot.EEPROM, Tier.Any),
        (Slot.HDD, Tier.Two)
      ).map(toPair)))

    api.IMC.registerAssemblerTemplate(
      "Leggings Upgrade",
      "li.cil.oc.common.template.ArmorTemplate.selectLeggings",
      "li.cil.oc.common.template.ArmorTemplate.validate",
      "li.cil.oc.common.template.ArmorTemplate.assemble",
      hostClass,
      null,Array(
        Tier.Three,
        Tier.Two,
        Tier.One
      ),
      asJavaIterable(Iterable(
        (Slot.Trim, Tier.Any),
        null,
        null,
        (Slot.CPU, Tier.Two),
        null,
        null,
        null,
        null,
      ).map(toPair)))

    api.IMC.registerAssemblerTemplate(
      "Boots Upgrade",
      "li.cil.oc.common.template.ArmorTemplate.selectBoots",
      "li.cil.oc.common.template.ArmorTemplate.validate",
      "li.cil.oc.common.template.ArmorTemplate.assemble",
      hostClass,
      null,Array(
        Tier.Three,
        Tier.Two,
        Tier.One
      ),
      asJavaIterable(Iterable(
        (Slot.Trim, Tier.Any),
        null,
        null,
        (Slot.CPU, Tier.Two),
        null,
        null,
        null,
        null,
      ).map(toPair)))

  }

  override protected def maxComplexity(inventory: Container) = super.maxComplexity(inventory) / 2 + 5

  override protected def caseTier(inventory: Container) = ItemUtils.caseTier(inventory.getItem(0))
}

