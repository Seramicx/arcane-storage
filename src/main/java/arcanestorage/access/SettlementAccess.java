package arcanestorage.access;

import necesse.engine.network.NetworkClient;
import necesse.engine.network.server.Server;
import necesse.engine.network.server.ServerClient;
import necesse.engine.world.worldData.SettlementsWorldData;
import necesse.level.maps.Level;
import necesse.level.maps.levelData.settlementData.NetworkSettlementData;
import necesse.level.maps.levelData.settlementData.ServerSettlementData;

/**
 * Whether a client may use a placed Arcane Storage object at a given tile.
 *
 * <p>Deliberately not {@code SettlementDependantContainer.hasSettlementAccess}, though it mirrors that method's
 * rule exactly for a tile that <i>is</i> inside a settlement. That class is not reused because extending it would
 * pull in the settlement-config UI (subscribe actions, the settlement panel forms) this mod's own containers already
 * opt out of on purpose -- see {@code StorageTerminalContainer}'s header. This class copies only the rule, not the
 * machinery.
 *
 * <p><b>One deliberate difference from the source it mirrors.</b> {@code hasSettlementAccess} returns {@code false}
 * when there is no settlement, because a {@code SettlementDependantContainer} should not exist without one --
 * {@code tick()} closes it otherwise. An Arcane Storage object has no such requirement: most are placed outside any
 * settlement's bounds, and treating "no settlement here" as "denied" would break every solo or no-settlement game.
 * So here, no settlement means nothing to restrict, and access is open.
 *
 * <p>Where there <i>is</i> a settlement, the rule is vanilla's own: open if the settlement is not private, has no
 * owner, the requester owns it, or the requester's team matches the settlement's team. Read {@code
 * NetworkSettlementData}'s own fields rather than a simplified "team must match" version -- an unowned or non-private
 * settlement is meant to be open to anyone, and narrowing that would deny access vanilla itself would grant.
 */
public final class SettlementAccess {

   private SettlementAccess() {
   }

   /**
    * @return {@code true} if {@code client} may use an Arcane Storage object at {@code (tileX, tileY)} on
    *     {@code level}. Always {@code true} outside any settlement's bounds.
    */
   public static boolean isAllowed(Level level, int tileX, int tileY, ServerClient client) {
      if (level == null || !level.isServer() || client == null) {
         return true;
      }

      Server server = level.getServer();
      if (server == null) {
         return true;
      }

      ServerSettlementData settlement = SettlementsWorldData.getSettlementsData(server)
         .getServerDataAtTile(level.getIdentifier(), tileX, tileY);
      if (settlement == null) {
         return true;
      }

      NetworkSettlementData data = settlement.networkData;
      if (!data.isPrivate() || !data.hasOwner()) {
         return true;
      }

      if (data.getOwnerAuth() == client.authentication) {
         return true;
      }

      int teamID = data.getTeamID();
      return teamID != -1 && teamID == client.getTeamID();
   }

   /**
    * Same rule as {@link #isAllowed(Level, int, int, ServerClient)}, for a check against a {@code NetworkClient}
    * that may not be a {@code ServerClient} (e.g. singleplayer's client-and-server-in-one). Prefer the
    * {@code ServerClient} overload when one is already in hand.
    */
   public static boolean isAllowed(Level level, int tileX, int tileY, NetworkClient client) {
      return client instanceof ServerClient ? isAllowed(level, tileX, tileY, (ServerClient)client) : true;
   }
}
