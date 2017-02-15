package net.codepoke.ai.challenges.hunterkiller.bots;

import static net.codepoke.ai.challenges.hunterkiller.StreamExtensions.stream;

import java.util.Collections;
import java.util.List;

import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerRules;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.MapLocation;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.enums.UnitType;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Unit;
import net.codepoke.ai.challenge.hunterkiller.orders.HunterKillerOrder;
import net.codepoke.ai.challenge.hunterkiller.orders.UnitOrder;
import net.codepoke.ai.challenges.hunterkiller.InfluenceMaps;
import net.codepoke.ai.challenges.hunterkiller.InfluenceMaps.KnowledgeBase;
import net.codepoke.ai.network.AIBot;
import net.codepoke.lib.util.datastructures.MatrixMap;
import one.util.streamex.StreamEx;

/**
 * Represents a bot used for testing purposes.
 * 
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
public class TestBot
		extends AIBot<HunterKillerState, HunterKillerAction> {

	HunterKillerRules rules = new HunterKillerRules();
	KnowledgeBase kb;
	private static final String KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE = "distance nearest enemy structure";
	private static final String KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_UNIT = "distance nearest enemy unit";
	private static final String KNOWLEDGE_LAYER_DISTANCE_TO_FRIENDLY_STRUCTURE = "distance nearest friendly structure";

	public TestBot() {
		super("", HunterKillerState.class, HunterKillerAction.class);

		// Create the knowledge-base that we will be using
		kb = new KnowledgeBase();
		kb.put(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE, InfluenceMaps::calculateDistanceToEnemyStructures);
		kb.put(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_UNIT, InfluenceMaps::calculateDistanceToEnemyUnits);
		kb.put(KNOWLEDGE_LAYER_DISTANCE_TO_FRIENDLY_STRUCTURE, InfluenceMaps::calculateDistanceToAlliedStructures);
	}

	@Override
	public HunterKillerAction handle(HunterKillerState state) {
		// string builders for debugging
		StringBuilder possibleCheckFails = new StringBuilder();
		StringBuilder orderFailures = new StringBuilder();

		HunterKillerAction action = new HunterKillerAction(state);
		int orderIndex = 0;

		// Make a copy of the state, so we can mutate it
		HunterKillerState copyState = state.copy();

		Player player = copyState.getActivePlayer();
		Map map = copyState.getMap();
		List<Structure> structures = player.getStructures(map);
		List<Unit> units = player.getUnits(map);
		List<Unit> enemyUnits = stream(map, Unit.class).filter(i -> !i.isControlledBy(player))
														.toList();

		// Update our knowledgebase
		kb.update(state);

		// We are currently testing differences between army composition, so only make one type of unit per player
		if (player.getName() == "A") {

			// This player only spawns Soldiers
			for (Structure structure : structures) {
				if (structure.canSpawn(state, UnitType.Soldier)) {
					HunterKillerOrder spawnOrder = structure.spawn(UnitType.Soldier);
					rules.addOrderIfPossible(action, orderIndex, copyState, spawnOrder, possibleCheckFails, orderFailures);
				}
			}
		} else if (player.getName() == "B") {

			// This player only spawns Medics
			for (Structure structure : structures) {
				if (structure.canSpawn(state, UnitType.Infected)) {
					HunterKillerOrder spawnOrder = structure.spawn(UnitType.Infected);
					rules.addOrderIfPossible(action, orderIndex, copyState, spawnOrder, possibleCheckFails, orderFailures);
				}
			}
		}

		// Handle the units
		for (Unit me : units) {
			MapLocation unitLocation = me.getLocation();

			// Check if there are any enemy units within our field-of-view
			List<Unit> enemiesInRange = StreamEx.of(enemyUnits)
												.filter(enemy -> me.isWithinAttackRange(enemy.getLocation()))
												.filter(enemy -> me.getFieldOfView()
																	.contains(enemy.getLocation()))
												.toList();

			// Check for special abilities before anything else
			if (me.canUseSpecialAttack()) {
				if (me.getType() == UnitType.Soldier && !enemiesInRange.isEmpty()) {

					// Find a suitable location to throw a grenade to
					MapLocation targetLocation = null;
					// Check all enemies we can see
					for (Unit enemy : enemiesInRange) {
						// Get the blast area of the attack, if targeted on this enemy
						List<MapLocation> aoe = map.getAreaAround(enemy.getLocation(), true);
						// Get the units in the blast area
						List<Unit> unitsInBlast = StreamEx.of(aoe)
															.filter(i -> map.getUnitAtLocation(i) != null)
															.map(i -> (Unit) map.getUnitAtLocation(i))
															.toList();

						// Check if there are any allies in the blast
						long friendsInBlast = StreamEx.of(unitsInBlast)
														.filter(i -> i.isControlledBy(player))
														.count();
						// Count the number of enemy units within the area
						long enemiesInBlast = StreamEx.of(unitsInBlast)
														.filter(i -> !i.isControlledBy(player))
														.count();

						// This location is suitable if there are 2 or more enemies and no friends
						if ((enemiesInBlast >= 2 && friendsInBlast == 0)
						// Or, the amount of enemies is higher than the amount of friends
							|| (enemiesInBlast > friendsInBlast && enemiesInBlast > 1)) {
							targetLocation = enemy.getLocation();
							// Don't search any further
							break;
						}
					}

					// Check if we found a target location
					if (targetLocation != null) {
						UnitOrder order = me.attack(targetLocation, true);

						// Add the order if it's possible
						if (rules.addOrderIfPossible(action, orderIndex, copyState, order, possibleCheckFails, orderFailures)) {
							// Don't create another order for this unit
							continue;
						}
					}

				} else if (me.getType() == UnitType.Medic) {
					Unit healTarget = null;

					// If there is a friend within range (and FoV) that could die to an attack of an enemy we can see
					List<Unit> friendInEmergency = StreamEx.of(units)
															.filter(friend -> {
																boolean needsHelp = false;
																for (Unit enemy : enemiesInRange) {
																	boolean canBeAttacked = enemy.isWithinAttackRange(friend.getLocation());
																	boolean wouldDie = friend.getHpCurrent() <= enemy.getAttackDamage();
																	needsHelp = canBeAttacked && wouldDie;
																	if (needsHelp)
																		break;
																}
																return needsHelp && me.isWithinAttackRange(friend.getLocation())
																		&& friend.isDamaged() && me.getFieldOfView()
																									.contains(friend.getLocation());
															})
															.toList();
					if (!friendInEmergency.isEmpty()) {
						healTarget = friendInEmergency.get(0);
					} else {

						// Heal the closest damaged friend
						List<Unit> damagedFriendsInRange = StreamEx.of(units)
																	.filter(friend -> me.isWithinAttackRange(friend.getLocation())
																						&& friend.isDamaged())
																	.sortedByInt(friend -> MapLocation.getManhattanDist(me.getLocation(),
																														friend.getLocation()))
																	.toList();
						if (!damagedFriendsInRange.isEmpty()) {
							healTarget = damagedFriendsInRange.get(0);
						}
					}

					// If we found a target to heal
					if (healTarget != null) {
						UnitOrder order = me.attack(healTarget.getLocation(), true);

						// Add the order if it's possible
						if (rules.addOrderIfPossible(action, orderIndex, copyState, order, possibleCheckFails, orderFailures)) {
							// Don't create another order for this unit
							continue;
						}
					}
				}
			}

			if (!enemiesInRange.isEmpty()) {
				Unit attackTarget = null;

				// Attack any enemy unit that we would be able to kill right now
				List<Unit> killTargets = StreamEx.of(enemiesInRange)
													.filter(enemy -> enemy.getHpCurrent() <= me.getAttackDamage())
													.sortedByInt(enemy -> MapLocation.getManhattanDist(	enemy.getLocation(),
																										me.getLocation()))
													.toList();
				if (!killTargets.isEmpty()) {
					attackTarget = killTargets.get(0);
				} else {
					// Attack the closest enemy unit
					attackTarget = StreamEx.of(enemiesInRange)
											.sortedByInt(enemy -> MapLocation.getManhattanDist(enemy.getLocation(), me.getLocation()))
											.findFirst()
											.get();
				}

				UnitOrder order = me.attack(attackTarget.getLocation(), false);
				// Add the order if it's possible
				if (rules.addOrderIfPossible(action, orderIndex, copyState, order, possibleCheckFails, orderFailures)) {
					// Don't create another order for this unit
					continue;
				}
			}

			// Check if any enemies that we know about are close to us
			long closeEnemies = StreamEx.of(enemyUnits)
										.filter(enemy -> me.isWithinRange(enemy.getLocation(), me.getAttackRange() + 1))
										.count();
			long closeFriends = StreamEx.of(units)
										.filter(friend -> me.isWithinRange(friend.getLocation(), me.getAttackRange() + 1))
										.count();

			if (closeEnemies > closeFriends) {
				// Flee
				// Check our knowledgebase about which locations are closest to a friendly structure
				MatrixMap friendsMap = kb.get(KNOWLEDGE_LAYER_DISTANCE_TO_FRIENDLY_STRUCTURE)
											.getMap();
				float minValue = friendsMap.get(unitLocation.getX(), unitLocation.getY());
				MapLocation minLocation = unitLocation;
				List<MapLocation> possibleLocations = state.getMap()
															.getNeighbours(unitLocation);
				Collections.shuffle(possibleLocations);
				for (MapLocation loc : possibleLocations) {
					float locValue = friendsMap.get(loc.getX(), loc.getY());
					if (locValue > 0 && locValue < minValue) {
						minValue = locValue;
						minLocation = loc;
					}
				}
				// Try to move to this location
				UnitOrder order = me.move(MapLocation.getDirectionTo(me.getLocation(), minLocation), map);
				// Add the order if it's possible
				if (rules.addOrderIfPossible(action, orderIndex, copyState, order, possibleCheckFails, orderFailures)) {
					// Don't create another order for this unit
					continue;
				}

			} else {
				// Advance
				// Check our knowledgebase about which locations are closest to an enemy structure
				MatrixMap foeMap = kb.get(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE)
										.getMap();
				float minValue = foeMap.get(unitLocation.getX(), unitLocation.getY());
				MapLocation minLocation = unitLocation;
				List<MapLocation> possibleLocations = state.getMap()
															.getNeighbours(unitLocation);
				Collections.shuffle(possibleLocations);
				for (MapLocation loc : possibleLocations) {
					float locValue = foeMap.get(loc.getX(), loc.getY());
					if (locValue > 0 && locValue < minValue) {
						minValue = locValue;
						minLocation = loc;
					}
				}

				// Try to move to this location
				UnitOrder order = me.move(MapLocation.getDirectionTo(me.getLocation(), minLocation), map);
				// Add the order if it's possible
				if (rules.addOrderIfPossible(action, orderIndex, copyState, order, possibleCheckFails, orderFailures)) {
					// Don't create another order for this unit
					continue;
				}

			}

		}

		//
		return action;
	}

}
