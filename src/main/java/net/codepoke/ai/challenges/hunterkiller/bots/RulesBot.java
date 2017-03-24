package net.codepoke.ai.challenges.hunterkiller.bots;

import static net.codepoke.ai.challenges.hunterkiller.StreamExtensions.stream;

import java.util.List;
import java.util.Optional;

import lombok.Getter;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerRules;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.MapLocation;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.enums.Direction;
import net.codepoke.ai.challenge.hunterkiller.enums.UnitOrderType;
import net.codepoke.ai.challenge.hunterkiller.enums.UnitType;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.GameObject;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Unit;
import net.codepoke.ai.challenge.hunterkiller.orders.StructureOrder;
import net.codepoke.ai.challenge.hunterkiller.orders.UnitOrder;
import net.codepoke.ai.network.AIBot;
import one.util.streamex.StreamEx;

import com.badlogic.gdx.utils.Array;

/**
 * Represents an {@link AIBot} for the HunterKiller game that generates orders for it's structures and units by
 * following a simple rules-hierarchy.
 * 
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
public class RulesBot
		extends AIBot<HunterKillerState, HunterKillerAction> {

	private static final boolean DEBUG_ImPossible = false;
	private static final boolean DEBUG_Fails = false;
	private static final String myUID = "93s71j2gded4odql4v432ihfo6";

	@Getter
	public final String botName = "RulesBot";
	
	HunterKillerRules rulesEngine = new HunterKillerRules();
	Array<Array<MapLocation>> unitPaths = new Array<Array<MapLocation>>(true, 5);

	public RulesBot() {
		super(myUID, HunterKillerState.class, HunterKillerAction.class);
	}

	@Override
	public HunterKillerAction handle(HunterKillerState state) {
		// string builders for debugging
		StringBuilder possibleCheckFails = new StringBuilder();
		StringBuilder orderFailures = new StringBuilder();

		// Create an action
		HunterKillerAction rulesAction = new HunterKillerAction(state);

		// Make a copy of the state, so we can mutate it
		HunterKillerState copyState = state.copy();

		// Prepare the state for this player, this is a temporary hack to simulate the server-environment
		copyState.prepare(state.getActivePlayerID());
		Player player = copyState.getActivePlayer();
		Map map = copyState.getMap();

		// Maintain a counter on the amount of orders we create, to correctly set their index in the action
		int orderCounter = 0;

		// Get some things we'll need to access
		List<Structure> structures = player.getStructures(map);
		List<Unit> units = player.getUnits(map);

		List<Structure> enemyStructures = stream(map, Structure.class).filter(i -> i.isUnderControl() && !i.isControlledBy(player))
																		.toList();
		List<Unit> enemyUnits = stream(map, Unit.class).filter(i -> !i.isControlledBy(player))
														.toList();
		List<GameObject> attackableEnemies = stream(map, GameObject.class).filter(i -> {
			if (i instanceof Unit) {
				return !((Unit) i).isControlledBy(player);
			} else if (i instanceof Structure) {
				Structure s = (Structure) i;
				return !s.isControlledBy(player) && s.isUnderControl() && s.isDestructible();
			} else
				return false;
		})
																			.toList();

		// Create orders for our structures
		RulesBot.createOrders(rulesEngine, rulesAction, orderCounter, structures, units, copyState, possibleCheckFails, orderFailures);

		// Go through our Units
		for (Unit unit : units) {

			// See if we have anything we need to react to
			UnitOrder reactiveOrder = getReactiveOrder(	rulesEngine,
														player,
														map,
														units,
														unit,
														attackableEnemies,
														copyState,
														possibleCheckFails);
			if (reactiveOrder != null) {
				if (rulesEngine.addOrderIfPossible(rulesAction, orderCounter, copyState, reactiveOrder, possibleCheckFails, orderFailures)) {
					continue;
				}
			}

			// See if we can get a strategic order
			UnitOrder strategicOrder = getStrategicOrder(	rulesEngine,
															player,
															map,
															units,
															unit,
															unitPaths,
															enemyStructures,
															enemyUnits,
															copyState,
															possibleCheckFails,
															orderFailures);
			if (strategicOrder != null) {
				if (rulesEngine.addOrderIfPossible(rulesAction, orderCounter, copyState, strategicOrder, possibleCheckFails, orderFailures)) {
					continue;
				}
			}

			// Do a random thing if nothing else
			UnitOrder randomOrder = RandomBot.createRandomOrder(unit, copyState);
			if (randomOrder != null) {
				if (rulesEngine.addOrderIfPossible(rulesAction, orderCounter, copyState, randomOrder, possibleCheckFails, orderFailures)) {
					continue;
				}
			}
		}

		if (DEBUG_ImPossible && possibleCheckFails.length() > 0) {
			System.out.printf(	"P(%s)R(%d)T(%d): some orders not possible, Reasons:%n%s%n",
								player.getName(),
								state.getCurrentRound(),
								state.getMap().currentTick,
								possibleCheckFails.toString());
		}
		if (DEBUG_Fails && orderFailures.length() > 0) {
			System.out.printf(	"P(%s)R(%d)T(%d): some orders failed, Reasons:%n%s%n",
								player.getName(),
								state.getCurrentRound(),
								state.getMap().currentTick,
								orderFailures.toString());
		}

		return rulesAction;
	}

	/**
	 * Adds an order for each structure in the provided list, if that structure could spawn a unit in the provided
	 * state. This method attempts to balance the types of units. Note that it also tries to execute the order on the
	 * provided state to determine if the order would succeed.
	 * 
	 * @param rules
	 *            The current rules being used for the game.
	 * @param action
	 *            The action that is being created.
	 * @param orderIndex
	 *            The index that the next order should have in the action's list of orders.
	 * @param structures
	 *            The structures the player is currently controlling.
	 * @param units
	 *            The units the player is currently controlling.
	 * @param stateCopy
	 *            The state of the game.
	 * @param possibleCheckFails
	 *            A way to collect any error/failure messages received from the rules during the check to see if an
	 *            order is possible.
	 * @param orderFails
	 *            A way to collect any error/failure messages received from executing the created orders on the provided
	 *            state.
	 */
	public static void createOrders(HunterKillerRules rules, HunterKillerAction action, int orderIndex, List<Structure> structures,
			List<Unit> units, HunterKillerState stateCopy, StringBuilder possibleCheckFails, StringBuilder orderFails) {
		// Go through our structures
		for (Structure structure : structures) {
			// Check if the structure can spawn anything in this state
			if (structure.canSpawn(stateCopy)) {
				// Spawn a type we don't have yet, or have the least of
				long soldierCount = units.stream()
											.filter(i -> i.getType() == UnitType.Soldier)
											.count();
				long medicCount = units.stream()
										.filter(i -> i.getType() == UnitType.Medic)
										.count();
				long infectedCount = units.stream()
											.filter(i -> i.getType() == UnitType.Infected)
											.count();

				boolean spawnUnit = false;
				UnitType spawnType = null;
				// Check infected first, these have highest priority
				if (infectedCount <= medicCount && infectedCount <= soldierCount) {
					spawnUnit = true;
					spawnType = UnitType.Infected;
				}
				// Next, check medics
				else if (medicCount <= soldierCount && medicCount <= infectedCount) {
					spawnUnit = true;
					spawnType = UnitType.Medic;
				}
				// Check soldiers
				else if (soldierCount <= medicCount && soldierCount <= infectedCount) {
					spawnUnit = true;
					spawnType = UnitType.Soldier;
				}

				// Check if we want to spawn a unit, have set the type we want, and if we can execute an order of that
				// type
				if (spawnUnit && spawnType != null && structure.canSpawn(stateCopy, spawnType)) {
					// Order the spawning of a medic
					StructureOrder order = structure.spawn(spawnType);
					// Add the order if it's possible
					if (rules.addOrderIfPossible(action, orderIndex, stateCopy, order, possibleCheckFails, orderFails)) {
						// Don't create another order for this object
						continue;
					}
				}
			}
		}
	}

	/**
	 * Returns a strategic order for the specified Unit. Null is returned if no strategic order can be found or is
	 * possible.
	 * {@link RulesBot#createOrders(HunterKillerRules, HunterKillerAction, int, List, List, HunterKillerState, StringBuilder, StringBuilder)}
	 * 
	 * @param player
	 *            The player that we are creating orders for.
	 * @param map
	 *            The current state of the game map.
	 * @param unit
	 *            The unit to create an order for.
	 * @param unitPaths
	 *            Any paths of movement that have been calculated for units, indexed by UnitID.
	 * @param enemyStructures
	 *            Any enemy structures currently visible to the player.
	 * @param enemyUnits
	 *            Any enemy units currently visible to the player.
	 */
	public static UnitOrder getStrategicOrder(HunterKillerRules rules, Player player, Map map, List<Unit> units, Unit unit,
			Array<Array<MapLocation>> unitPaths, List<Structure> enemyStructures, List<Unit> enemyUnits, HunterKillerState stateCopy,
			StringBuilder possibleCheckFails, StringBuilder orderFails) {

		// Make sure we can accommodate a path for this unit
		if (unitPaths.size <= unit.getID())
			unitPaths.setSize(unit.getID() + 1);

		// Check if this unit is on a path
		if (unitPaths.get(unit.getID()) != null) {
			Array<MapLocation> path = unitPaths.get(unit.getID());
			boolean onPath = true;
			// Check if the first location on the path is our current location
			if (path.first()
					.equals(unit.getLocation())) {
				// Remove it
				path.removeIndex(0);
			}
			// Check if we are somewhere in the middle of the path
			else if (path.contains(unit.getLocation(), false)) {
				int cPos = path.indexOf(unit.getLocation(), false);
				// Remove up to and including
				path.removeRange(0, cPos);
			} else {
				onPath = false;
			}

			// If we are still on the path, move to the next location
			if (onPath && path.size > 0) {
				UnitOrder order = unit.move(MapLocation.getDirectionTo(unit.getLocation(), path.first()), map);
				if (rules.isOrderPossible(stateCopy, order, possibleCheckFails))
					return order;
			} else {
				// We lost the path somewhere, reset it
				unitPaths.set(unit.getID(), null);
			}
		} else {
			// Find the closest enemy structure
			Optional<Structure> closestEnemyStructure = StreamEx.of(enemyStructures)
																.sortedBy(i -> MapLocation.getManhattanDist(unit.getLocation(),
																											i.getLocation()))
																.findFirst();
			if (closestEnemyStructure.isPresent()) {
				MapLocation targetLocation = null;

				if (!closestEnemyStructure.get()
											.isWalkable()) {
					// We can't plan a path to the structure itself, since it's not walkable, so get any location
					// next to it
					List<MapLocation> nextToStructure = map.getAreaAround(closestEnemyStructure.get()
																								.getLocation(), false);
					Optional<MapLocation> closestToTargetLocation = StreamEx.of(nextToStructure)
																			.sortedByInt(i -> MapLocation.getManhattanDist(	unit.getLocation(),
																															i))
																			.findFirst();
					// If we found any location close to our target
					if (closestToTargetLocation.isPresent()) {
						targetLocation = closestToTargetLocation.get();
					}
				} else {
					// We can walk over the structure, so set its location as target
					targetLocation = closestEnemyStructure.get()
															.getLocation();
				}

				Array<MapLocation> path = map.findPath(unit.getLocation(), targetLocation);
				// Check if anything was found
				if (path.size > 0) {

					// Remember the path
					unitPaths.set(unit.getID(), path);
					// Move to the first location
					UnitOrder order = unit.move(MapLocation.getDirectionTo(unit.getLocation(), path.first()), map);
					if (rules.isOrderPossible(stateCopy, order, possibleCheckFails))
						return order;
				}
			}
		}

		// No strategic order possible or needed, return null
		return null;
	}

	/**
	 * Returns a reactive order for the specified Unit. Null is returned if no reactive order can be found or is
	 * possible.
	 * {@link RulesBot#getStrategicOrder(HunterKillerRules, Player, Map, List, Unit, Array, List, List, HunterKillerState, StringBuilder, StringBuilder)}
	 */
	public static UnitOrder getReactiveOrder(HunterKillerRules rules, Player player, Map map, List<Unit> units, Unit unit,
			List<GameObject> enemies, HunterKillerState stateCopy, StringBuilder possibleCheckFails) {

		// Check if we can capture a structure we do not control
		List<Structure> capturableStructures = stream(map, Structure.class).filter(i -> !i.isControlledBy(player)
																						&& i.isCapturable()
																						&& MapLocation.getManhattanDist(unit.getLocation(),
																														i.getLocation()) == 1)
																			.toList();
		if (!capturableStructures.isEmpty()) {
			UnitOrder order = new UnitOrder(unit, UnitOrderType.MOVE, capturableStructures.get(0)
																							.getLocation());
			if (rules.isOrderPossible(stateCopy, order, possibleCheckFails)) {
				return order;
			}
		}

		// Get a collection of all enemy Units we can see and reach
		List<GameObject> enemiesInRange = StreamEx.of(enemies)
													.filter(enemy -> unit.isWithinAttackRange(enemy.getLocation()))
													.sortedByInt(enemy -> MapLocation.getManhattanDist(	unit.getLocation(),
																										enemy.getLocation()))
													.toList();

		// Check if the unit can use its special attack
		if (unit.canUseSpecialAttack()) {

			if (unit.getType() == UnitType.Soldier) {
				// Filter enemies on those we can see
				List<GameObject> enemiesInRangeAndFoV = StreamEx.of(enemiesInRange)
																.filter(enemy -> unit.getFieldOfView()
																						.contains(enemy.getLocation()))
																.toList();
				if (!enemiesInRangeAndFoV.isEmpty()) {
					// Find a suitable location to throw a grenade to
					MapLocation targetLocation = null;
					// Check all enemies we can see
					for (GameObject enemy : enemiesInRange) {
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
						UnitOrder order = unit.attack(targetLocation, true);
						if (rules.isOrderPossible(stateCopy, order, possibleCheckFails))
							return order;
					}
				}

			} else if (unit.getType() == UnitType.Medic) {
				Unit healTarget = null;

				// Get a collection of allies that are damaged and within our range
				List<Unit> damagedFriendsInRange = StreamEx.of(units)
															.filter(friend -> unit.isWithinAttackRange(friend.getLocation())
																				&& friend.isDamaged())
															.sortedByInt(friend -> MapLocation.getManhattanDist(unit.getLocation(),
																												friend.getLocation()))
															.toList();

				// Filter on allies within range (and FoV) that could die to an attack of an enemy we can see
				List<Unit> friendInEmergency = StreamEx.of(damagedFriendsInRange)
														.filter(friend -> {
															boolean needsHelp = false;
															for (GameObject enemy : enemiesInRange) {
																if (enemy instanceof Unit) {
																	Unit enemyUnit = (Unit) enemy;
																	boolean canBeAttacked = enemyUnit.isWithinAttackRange(friend.getLocation());
																	boolean wouldDie = friend.getHpCurrent() <= enemyUnit.getAttackDamage();
																	needsHelp = canBeAttacked && wouldDie;
																	if (needsHelp)
																		break;
																}
															}
															return needsHelp && unit.isWithinAttackRange(friend.getLocation())
																	&& friend.isDamaged() && unit.getFieldOfView()
																									.contains(friend.getLocation());
														})
														.sortedByInt(friend -> MapLocation.getManhattanDist(unit.getLocation(),
																											friend.getLocation()))
														.toList();
				if (!friendInEmergency.isEmpty()) {
					// Heal the closest ally in an emergency
					healTarget = friendInEmergency.get(0);
				} else {
					// Filter on allies within our field-of-view
					List<Unit> visibleDamagedFriendInRange = StreamEx.of(damagedFriendsInRange)
																		.filter(friend -> unit.getFieldOfView()
																								.contains(friend.getLocation()))
																		.sortedByInt(friend -> MapLocation.getManhattanDist(unit.getLocation(),
																															friend.getLocation()))
																		.toList();
					// Heal the closest damaged ally we can see
					if (!visibleDamagedFriendInRange.isEmpty()) {
						healTarget = visibleDamagedFriendInRange.get(0);
					} else {

						// Filter on allies within range, but not in our field-of-view
						List<Unit> invisibleDamagedFriendInRange = StreamEx.of(damagedFriendsInRange)
																			.filter(friend -> !unit.getFieldOfView()
																									.contains(friend.getLocation()))
																			.sortedByInt(friend -> MapLocation.getManhattanDist(unit.getLocation(),
																																friend.getLocation()))
																			.toList();
						// Turn towards the closest ally we cannot see
						if (!invisibleDamagedFriendInRange.isEmpty()) {
							Direction directionToFriend = MapLocation.getDirectionTo(	unit.getLocation(),
																						invisibleDamagedFriendInRange.get(0)
																														.getLocation());
							// Check if any direction could be found, and we are not already facing that direction
							if (directionToFriend != null && unit.getOrientation() != directionToFriend) {
								UnitOrder order = unit.rotate(Direction.rotationRequiredToFace(unit, directionToFriend));
								if (rules.isOrderPossible(stateCopy, order, possibleCheckFails))
									return order;
							}
						}
					}
				}

				// If we found a target to heal
				if (healTarget != null) {
					UnitOrder order = unit.attack(healTarget.getLocation(), true);
					if (rules.isOrderPossible(stateCopy, order, possibleCheckFails))
						return order;
				}
			}
		}

		// If there are any enemies in range
		if (!enemiesInRange.isEmpty()) {
			// Filter enemies on those we can see
			List<GameObject> visibleEnemiesInRange = StreamEx.of(enemiesInRange)
																.filter(enemy -> unit.getFieldOfView()
																						.contains(enemy.getLocation()))
																.toList();
			if (!visibleEnemiesInRange.isEmpty()) {
				GameObject attackTarget = null;

				// Attack any enemy unit that we would be able to kill right now
				List<GameObject> killTargets = StreamEx.of(visibleEnemiesInRange)
														.filter(enemy -> enemy.getHpCurrent() <= unit.getAttackDamage())
														.sortedByInt(enemy -> MapLocation.getManhattanDist(	enemy.getLocation(),
																											unit.getLocation()))
														.toList();
				if (!killTargets.isEmpty()) {
					attackTarget = killTargets.get(0);
				} else {
					// Attack the closest enemy unit
					attackTarget = StreamEx.of(visibleEnemiesInRange)
											.sortedByInt(enemy -> MapLocation.getManhattanDist(enemy.getLocation(), unit.getLocation()))
											.findFirst()
											.get();
				}

				UnitOrder order = unit.attack(attackTarget.getLocation(), false);
				if (rules.isOrderPossible(stateCopy, order, possibleCheckFails))
					return order;
			} else {

				// Filter on enemies within range, but not in our field-of-view
				List<GameObject> invisibleEnemiesInRange = StreamEx.of(enemiesInRange)
																	.filter(enemy -> !unit.getFieldOfView()
																							.contains(enemy.getLocation()))
																	.sortedByInt(enemy -> MapLocation.getManhattanDist(	unit.getLocation(),
																														enemy.getLocation()))
																	.toList();
				// Turn towards the closest enemy we cannot see
				if (!invisibleEnemiesInRange.isEmpty()) {
					Direction directionToEnemy = MapLocation.getDirectionTo(unit.getLocation(), invisibleEnemiesInRange.get(0)
																														.getLocation());
					// Check if any direction could be found, and we are not already facing that direction
					if (directionToEnemy != null && unit.getOrientation() != directionToEnemy) {
						UnitOrder order = unit.rotate(Direction.rotationRequiredToFace(unit, directionToEnemy));
						if (rules.isOrderPossible(stateCopy, order, possibleCheckFails))
							return order;
					}
				}
			}
		}

		// If looking directly at a wall, rotate
		// if (map.getFeatureAtLocation(map.getLocationInDirection(unit.getLocation(), unit.getOrientation(), 1))
		// instanceof Wall) {
		// UnitOrder order = unit.rotate(r.nextBoolean());
		// if (rules.isOrderPossible(stateCopy, order, possibleCheckFails))
		// return order;
		// }

		// No reactive order possible or needed, return null
		return null;
	}

}
