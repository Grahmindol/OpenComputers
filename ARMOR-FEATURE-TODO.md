TODO List: Modular Armor System for OpenComputers
1. Armor Features
   - [x] Added slots for armor trims.
     - new background icon
   - [x] Created ArmorWrapper
     - Works on the same principle as TabletWrapper.
     - Instantiated from a Player entity (one functional set per player).
     - Aggregates inventories (oc:contents) from each equipped armor piece.
     - Hosts and runs the global Machine instance.
     - Calculates and stores the armor set's checksum.
   - [x] Created ArmorManager
     - Works on the same principle as Tablet.
     - Invalidates and reloads the set when a piece is modified.
     - Added keyboard shortcut ('O' key) to open the client-side Screen.
     - Sends a dedicated network packet (OpenArmorGuiPacket) from client to server when 'O' is pressed.
   - [x] Created Armor component
       - [ ] Dynamic armor color modification. - Added a custom trim ingredient that sends an RGB code
         - Then used a mixin on the method `net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer.renderTrim(net.minecraft.core.Holder<net.minecraft.world.item.ArmorMaterial>, com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.renderer.MultiBufferSource, int, net.minecraft.world.item.armortrim.ArmorTrim, net.minecraft.client.model.Model, boolean)`
         - Just need to add a color as the final argument to `renderToBuffer`
         - AWAITING CREATIVE TAB PR for the mixin file to be created
       - [ ] Power Information
   - [ ] Optimized invalidation in ArmorManager: dynamically connect/disconnect network components without shutting down the machine, provided the chestplate remains equipped.
   - [ ] Support for quick interaction with the container/inventory (open container if the player is crouching and typing 'O').
   - [ ] Created new dedicated items instead of modifying default armor (Pending new creative tab system before touching OCItems).
   - [ ] Automatic armor recharging when within range of a charger (Charger block).
   - [ ] Support all Robot upgrade
   - [ ] TEST AND PATCH ALL BUGS
2. Miscellaneous & Environmental Interactions
   - [ ] Allow an Adapter to access the network of armor placed on an armor stand. (at least FileSytem and EEPROM)
   - [ ] Add direct connection with eaten nanomachine
   - [ ] Disk and EEPROM Access in a Charger  (modeled after the tablet).
3. Upgrades & Extensions
   - [ ] Elytra Upgrade: Allows the player to fly just like with elytra.
   - [ ] Gold Upgrade: Makes the player neutral towards Piglins.
   - [ ] Nanoparticle Upgrade: Automatically repairs armor durability by consuming energy.
   - [ ] Respiration Upgrade: Stores oxygen for survival underwater and in hostile environments.
   - [ ] Turret Upgrade: Adds a defense system that fires laser beams. (may be in OpenSecurity once this fabulous port is done)
   - [ ] HUD Upgrade: Like in https://www.curseforge.com/minecraft/mc-mods/openglasses
