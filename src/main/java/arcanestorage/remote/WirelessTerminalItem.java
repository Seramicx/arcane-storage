package arcanestorage.remote;

import arcanestorage.object.UnitTier;
import arcanestorage.object.WirelessTransceiverObject;
import necesse.engine.localization.Localization;
import necesse.engine.network.server.ServerClient;
import necesse.entity.mobs.PlayerMob;
import necesse.entity.mobs.itemAttacker.ItemAttackSlot;
import necesse.entity.mobs.itemAttacker.ItemAttackerMob;
import necesse.gfx.gameTooltips.ListGameTooltips;
import necesse.inventory.InventoryItem;
import necesse.inventory.item.Item;
import necesse.inventory.item.ItemInteractAction;
import necesse.engine.registries.ItemRegistry;
import necesse.engine.network.gameNetworkData.GNDItemMap;
import necesse.level.maps.Level;

/**
 * The Wireless Terminal, at one rung of its ladder: a carried item paired to one Wireless Transceiver.
 *
 * <h2>What the tier buys</h2>
 *
 * <p>Nothing about the window. Every tier opens the same network with the same slots, because a storage terminal
 * that showed less at a lower tier would be a worse interface rather than a smaller reach. What the ladder gates is
 * <b>where the player may be standing</b>, and that rule lives in {@link Reach} because the container has to apply
 * it every tick as well.
 *
 * <p>Both ends carry a tier and the lower one governs, so a player who upgrades one end and not the other gets told
 * which. See {@link Reach#effective}.
 */
public class WirelessTerminalItem extends Item implements ItemInteractAction {

   public final UnitTier tier;

   public WirelessTerminalItem(UnitTier tier) {
      super(1);
      this.tier = tier;
      this.rarity = tier == UnitTier.FALLEN ? Item.Rarity.EPIC : Item.Rarity.UNCOMMON;
   }

   @Override
   public ListGameTooltips getTooltips(InventoryItem item, PlayerMob perspective, necesse.engine.util.GameBlackboard blackboard) {
      ListGameTooltips tooltips = super.getTooltips(item, perspective, blackboard);
      RemoteBinding binding = RemoteBinding.read(item);
      if (binding == null) {
         tooltips.add(Localization.translate("ui", "arcanestorage_wireless_unpaired"));
      } else {
         tooltips.add(Localization.translate("ui", "arcanestorage_wireless_paired",
               "x", String.valueOf(binding.tileX), "y", String.valueOf(binding.tileY)));
      }

      // The reach this end allows. Stated on the item as well as on the transceiver because the lower of the two
      // decides, so a player comparing them needs both numbers visible.
      tooltips.add(Reach.describe(this.tier));

      return tooltips;
   }

   /**
    * True for any tile, so a click always uses the item rather than swinging it -- when nothing else claims
    * the click first. See {@link #overridesObjectInteract} for what "first" means here.
    *
    * <p>Deliberately not restricted to transceivers. Restricting it would make the item inert everywhere except in
    * front of the thing it is meant to replace visiting.
    */
   @Override
   public boolean canLevelInteract(Level level, int x, int y, ItemAttackerMob attackerMob, InventoryItem item) {
      return attackerMob != null && attackerMob.isPlayer;
   }

   /**
    * False, so a chest, terminal, or any other placed object gets first refusal at a click, exactly as it would
    * for any other held item -- and the wireless terminal only opens its own network when nothing else answered.
    *
    * <p>Used to be {@code true} unconditionally, which is what made holding this item so disruptive: a click
    * that should have opened a chest across the room opened the wireless network instead, every time, because
    * {@code overridesObjectInteract} ran before the engine ever looked for an object at the clicked tile (see
    * {@code PlayerMob.runClientInteract}) and this method gave it no reason not to. The engine's own fallback
    * already does the right thing without any override here: an item is only asked at all once no object has
    * claimed the click, which is exactly "open the network when there is nothing else to click on".
    *
    * <p>The one place this used to matter -- pairing to a Wireless Transceiver, which is itself always an
    * interactable object and would therefore never fall through to the item at all -- is handled by
    * {@link WirelessTransceiverObject#interact} checking what the player is holding before it opens its own
    * upgrade panel, rather than by this item claiming the click first. See {@link #pairTo} and that method's
    * own doc for why the check belongs there and not here.
    */
   @Override
   public boolean overridesObjectInteract(Level level, PlayerMob player, InventoryItem item) {
      return false;
   }

   /**
    * Opens what the item is paired to, when nothing else has claimed the click.
    *
    * <p><b>The engine runs this on both sides</b> -- {@code PlayerMob.runClientItemLevelInteract} calls it on the
    * clicking client so the swing and any change to the item are immediate, and the server then runs it for real.
    * The side is therefore decided once, here, and the client returns before any server-only API exists to be
    * misused. An earlier version instead carried a possibly-null {@code ServerClient} into its helpers, which
    * null-checked it in two places and dereferenced it in a third, and threw on the first right-click in game.
    * Deciding once is worth more than checking three times.
    *
    * <p>Pairing no longer happens here: a Wireless Transceiver is always an interactable object at its own tile,
    * so now that {@link #overridesObjectInteract} is false, this method is never even reached when the player
    * clicks one -- {@link WirelessTransceiverObject#interact} runs instead, and calls {@link #pairTo} itself.
    */
   @Override
   public InventoryItem onLevelInteract(Level level, int x, int y, ItemAttackerMob attackerMob, int attackHeight,
         InventoryItem item, ItemAttackSlot slot, int seed, GNDItemMap mapContent) {
      if (!attackerMob.isPlayer) {
         return item;
      }

      if (!level.isServer()) {
         // The client predicts nothing here: opening a container arrives as PacketOpenContainer rather than
         // being opened locally, and the chat messages below are the server's alone to send.
         return item;
      }

      ServerClient client = ((PlayerMob)attackerMob).getServerClient();
      if (client == null) {
         return item;
      }

      this.open(client, item);
      return item;
   }

   /**
    * Binds a wireless terminal to the transceiver at {@code (x, y)}, replacing any previous pairing.
    *
    * <p>Called from {@link WirelessTransceiverObject#interact} rather than from this item's own
    * {@link #onLevelInteract}, because {@link #overridesObjectInteract} is false: the transceiver is always the
    * object found first at its own tile, so the item is never asked at all when one is clicked. The transceiver
    * checks what the player is holding before running its normal (upgrade-panel) interaction, and calls this
    * method instead when it finds a wireless terminal -- the one case where the object's own default behaviour
    * needs to be pre-empted rather than left to win outright.
    *
    * <p>Runs on both sides for the same reason {@link #onLevelInteract} used to: the client predicts the write to
    * its own copy of the item so the tooltip is right immediately, and says nothing else, since a chat message
    * from the client would double up with the server's once its own copy of this method runs moments later.
    *
    * <p><b>{@code x, y} are already tile coordinates, not level pixels.</b> They arrive via
    * {@code GameObject.interact(Level, int, int, PlayerMob)}, whose caller is {@code LevelObject.interact}:
    * {@code this.object.interact(this.level, this.tileX, this.tileY, player)}. That is a different contract
    * from {@code ItemInteractAction.onLevelInteract}'s {@code x, y}, which *are* level pixels and need
    * {@code GameMath.getTileCoordinate} to become a tile -- the method this replaced. Applying that conversion
    * here divided an already-small tile coordinate down further and landed on the wrong tile entirely, which
    * is what a `ClassCastException` from `AirObject` to `WirelessTransceiverObject` on this line was: not a
    * corrupt binding, a tile lookup at the wrong place.
    */
   public void pairTo(Level level, int tileX, int tileY, PlayerMob player, InventoryItem item) {
      if (!level.isServer()) {
         new RemoteBinding(level, tileX, tileY).write(item);
         return;
      }

      ServerClient client = player.getServerClient();
      if (client == null) {
         return;
      }

      RemoteBinding binding = new RemoteBinding(level, tileX, tileY);
      RemoteBinding existing = RemoteBinding.read(item);

      if (binding.equals(existing)) {
         // Re-pairing to the same transceiver is not an error, and saying nothing would read as the click
         // having missed. It is also the natural way a player checks that pairing worked.
         client.sendChatMessage(Localization.translate("ui", "arcanestorage_wireless_alreadypaired"));
         return;
      }

      binding.write(item);

      // Pairing to a transceiver of a different tier is allowed, and the mismatch is worth saying at the moment it
      // is created rather than the first time the reach falls short of what the better end promised.
      UnitTier transceiver = ((WirelessTransceiverObject)level.getObject(tileX, tileY)).tier;
      if (transceiver != this.tier) {
         UnitTier governing = Reach.effective(this.tier, transceiver);
         client.sendChatMessage(Localization.translate("ui", "arcanestorage_wireless_tiermismatch",
               "tier", Localization.translate("ui", "arcanestorage_tier_" + governing.name().toLowerCase())));
      }

      // item is the live reference the slot itself holds (PlayerMob.getSelectedItem() does not copy), so
      // mutating its GND map via binding.write above is already enough to make the change stick; nothing here
      // needs to write it back to a slot the way onLevelInteract's return value used to.
      client.sendChatMessage(Localization.translate("ui", "arcanestorage_wireless_pairedto",
            "x", String.valueOf(tileX), "y", String.valueOf(tileY)));
   }

   /** Opens the paired network, loading its level if need be, or explains why it cannot. */
   private void open(ServerClient client, InventoryItem item) {
      RemoteBinding binding = RemoteBinding.read(item);
      RemoteTerminal.Resolved resolved = RemoteTerminal.resolve(client, binding);
      switch (resolved.result) {
         case UNPAIRED:
            client.sendChatMessage(Localization.translate("ui", "arcanestorage_wireless_unpaired"));
            return;
         case BAD_LEVEL:
            // Distinguished on the console but not to the player, who has one thing to do about either. Worth
            // separating at all because they read identically in chat, and that ambiguity sent an hour of
            // diagnosis at the level unload when the real cause was the region under it.
            System.out.println("Arcane Storage: a wireless terminal's level could not be resolved: " + binding);
            client.sendChatMessage(Localization.translate("ui", "arcanestorage_wireless_gone"));
            return;
         case GONE:
            client.sendChatMessage(Localization.translate("ui", "arcanestorage_wireless_gone"));
            return;
         case DENIED:
            client.sendChatMessage(Localization.translate("ui", "arcanestorage_access_denied"));
            return;
         default:
            Reach.Decision decision = Reach.check(client.playerMob, this.tier, resolved.tier(),
                  binding.levelID, binding.tileX, binding.tileY);
            if (!decision.ok()) {
               refuse(client, decision);
               return;
            }

            RemoteTerminalContainer.openAndSend(client, binding, resolved);
      }
   }

   /**
    * Says why, and what would fix it.
    *
    * <p>Two messages rather than one sentence: the reason is about the world and the hint is about equipment, and a
    * player who already knows they are out of range should not have to read the distance again to find the part
    * they did not know.
    */
   static void refuse(ServerClient client, Reach.Decision decision) {
      if (decision.verdict == Reach.Verdict.WRONG_LEVEL) {
         client.sendChatMessage(Localization.translate("ui", "arcanestorage_wireless_wronglevel"));
      } else {
         client.sendChatMessage(Localization.translate("ui", "arcanestorage_wireless_toofar",
               "distance", String.valueOf(decision.distance), "limit", String.valueOf(decision.limit)));
      }

      String what = upgradeTarget(decision);
      if (what != null) {
         client.sendChatMessage(Localization.translate("ui", "arcanestorage_wireless_upgrade", "what", what));
      }
   }

   /** Which end to upgrade, or null when no upgrade would help. */
   private static String upgradeTarget(Reach.Decision decision) {
      if (decision.upgradeTerminal && decision.upgradeTransceiver) {
         return Localization.translate("ui", "arcanestorage_wireless_upgradeboth");
      }

      if (decision.upgradeTerminal) {
         return Localization.translate("ui", "arcanestorage_wireless_terminalname");
      }

      return decision.upgradeTransceiver
            ? Localization.translate("ui", "arcanestorage_wireless_transceivername")
            : null;
   }

   /**
    * The best tier of wireless terminal in a player's inventory that is paired to a given transceiver, or null when
    * they are carrying none.
    *
    * <p>Read live rather than captured when the container opened, because the item is the link: putting it in a
    * chest, or swapping it for a lower rung, changes what the player is entitled to see. The alternative -- trusting
    * the tier that opened the window -- would let a player open at Fallen range and then bank the terminal.
    *
    * <p>Scans the bag only, which is the same inventory the tooltip and the crafting UI count. A terminal in a chest
    * is not in the player's hand by any reading.
    */
   static UnitTier heldTier(PlayerMob player, RemoteBinding binding) {
      if (player == null || player.getInv() == null || binding == null) {
         return null;
      }

      UnitTier best = null;

      // The bag and the hotbar, and nothing else: the same four flags UnitUpgrade counts with, for the same reason.
      // A terminal in cloud storage or in an inactive armour set is not in the player's hand by any reading.
      java.util.Iterator<necesse.inventory.InventorySlot> slots =
            player.getInv().streamInventorySlots(false, false, false, false).iterator();

      while (slots.hasNext()) {
         InventoryItem held = slots.next().getItem();
         if (held == null || !(held.item instanceof WirelessTerminalItem)) {
            continue;
         }

         if (!binding.equals(RemoteBinding.read(held))) {
            continue;
         }

         UnitTier tier = ((WirelessTerminalItem)held.item).tier;
         if (best == null || tier.ordinal() > best.ordinal()) {
            best = tier;
         }
      }

      return best;
   }

   /** The registered item for a tier, for recipes and for tests that need one without a registry lookup by hand. */
   public static Item of(UnitTier tier) {
      return ItemRegistry.getItem(tier.wirelessTerminalId());
   }
}
