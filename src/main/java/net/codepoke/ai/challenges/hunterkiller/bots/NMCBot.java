package net.codepoke.ai.challenges.hunterkiller.bots;

import java.util.List;
import java.util.concurrent.TimeUnit;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.val;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerRules;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.Map;
import net.codepoke.ai.challenge.hunterkiller.MoveGenerator;
import net.codepoke.ai.challenge.hunterkiller.Player;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.GameObject;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Unit;
import net.codepoke.ai.challenge.hunterkiller.orders.HunterKillerOrder;
import net.codepoke.ai.challenge.hunterkiller.orders.StructureOrder;
import net.codepoke.ai.challenge.hunterkiller.orders.UnitOrder;
import net.codepoke.ai.challenges.hunterkiller.InfluenceMaps;
import net.codepoke.ai.challenges.hunterkiller.InfluenceMaps.KnowledgeBase;
import net.codepoke.ai.challenges.hunterkiller.bots.HMCTSBot.ActionCompletionStrategy;
import net.codepoke.ai.challenges.hunterkiller.bots.HMCTSBot.RandomActionCompletion;
import net.codepoke.ai.challenges.hunterkiller.bots.evaluation.HunterKillerStateEvaluation;
import net.codepoke.ai.challenges.hunterkiller.bots.sorting.ControlledObjectSortingStrategy;
import net.codepoke.ai.challenges.hunterkiller.bots.sorting.StaticSorting;
import net.codepoke.lib.util.ai.SearchContext;
import net.codepoke.lib.util.ai.SearchContext.Status;
import net.codepoke.lib.util.ai.State;
import net.codepoke.lib.util.ai.game.Move;
import net.codepoke.lib.util.ai.search.ApplicationStrategy;
import net.codepoke.lib.util.ai.search.ExpansionStrategy;
import net.codepoke.lib.util.ai.search.GoalStrategy;
import net.codepoke.lib.util.ai.search.PlayoutStrategy;
import net.codepoke.lib.util.ai.search.StateEvaluation;
import net.codepoke.lib.util.ai.search.tree.TreeSearchNode;
import net.codepoke.lib.util.ai.search.tree.nmc.NaiveMonteCarloRootNode;
import net.codepoke.lib.util.ai.search.tree.nmc.NaiveMonteCarloSearch.NaiveMonteCarloSearchBuilder;
import net.codepoke.lib.util.common.Stopwatch;
import net.codepoke.lib.util.datastructures.MatrixMap;
import net.codepoke.lib.util.functions.Function2;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.IntMap;

/**
 * Bot for the game of HunterKiller that uses a NaiveMonteCarloSearch to determine its actions.
 * 
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
public class NMCBot
		extends BaseBot<HunterKillerState, HunterKillerAction> {

	/**
	 * Unique identifier, supplied by the AI-Competition for HunterKiller.
	 */
	private static final String myUID = "";
	/**
	 * Name of this bot as it is registered to the AI-Competition for HunterKiller.
	 */
	@Getter
	public String botName = "NMCBot";

	/**
	 * Rules of HunterKiller.
	 */
	HunterKillerRules rulesEngine = new HunterKillerRules();

	/**
	 * The knowledgebase we are using.
	 */
	KnowledgeBase kb;
	/**
	 * String used to identify the knowledge-layer that contains the distance to any enemy structure.
	 */
	private static final String KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE = "distance nearest enemy structure";
	/**
	 * Number indicating after which round the knowledgebase should not be updated anymore.
	 */
	private static final int KNOWLEDGEBASE_UPDATE_THRESHOLD_ROUND_NUMBER = 1;

	/** These constants define the chances of exploration vs. exploitation in both the parent and child searches. */
	private static final float EPSILON_PARENT_SEARCH = 0.25f;
	private static final float EPSILON_CHILD_SEARCH = 0.25f;
	/**
	 * Number of iterations to use.
	 */
	private static final int NMC_NUMBER_OF_ITERATIONS = 1000;

	/**
	 * Bot that can be called to simulate actions during a Monte-Carlo playout.
	 */
	private BaseBot<HunterKillerState, HunterKillerAction> playoutBot;
	/**
	 * Handles the simulation phase of a Monte-Carlo Search.
	 */
	private PlayoutStrategy<Object, NMCState, CombinedAction, Object> playout;
	/**
	 * A way of completing a combined action during a MCTS-playout.
	 */
	private ActionCompletionStrategy actionCompletion;
	/**
	 * Contains the logic for the phases in Monte-Carlo Search.
	 */
	private NMCGameLogic gameLogic;
	/**
	 * The strategy used to sort the dimensions.
	 */
	private ControlledObjectSortingStrategy sorting;
	/**
	 * Strategy that determines if the goal of a search/playout has been reached.
	 */
	private GoalStrategy<Object, NMCState, CombinedAction, Object> goal;
	/**
	 * Evaluation function for a NMCState.
	 */
	private StateEvaluation<NMCState, CombinedAction, TreeSearchNode<NMCState, PartialAction>> evaluation;
	/**
	 * Merges sub-actions from the individual dimensions into a combined-action.
	 */
	@SuppressWarnings("rawtypes")
	private Function2 merger = new Function2<CombinedAction, NMCState, Array<TreeSearchNode<NMCState, PartialAction>>>() {

		@Override
		public CombinedAction apply(NMCState state, Array<TreeSearchNode<NMCState, PartialAction>> tree) {
			CombinedAction action = new CombinedAction(state.getPlayer(), state.combinedAction.currentOrdering);
			for (TreeSearchNode<NMCState, PartialAction> node : tree) {
				action.pushOrder(node.getPayload());
			}
			return action;
		}

	};

	/**
	 * Number of rounds after which the playout of a node is cut off.
	 */
	private static final int PLAYOUT_ROUND_CUTOFF = 20;

	/** These numbers indicate the reward that is used in the evaluation function for a game win or loss. */
	private static final int GAME_WIN_EVALUATION = 100000;
	private static final int GAME_LOSS_EVALUATION = -1 * GAME_WIN_EVALUATION;

	public NMCBot() {
		this(null);
	}

	public NMCBot(BaseBot<HunterKillerState, HunterKillerAction> botForPlayout) {
		super(myUID, HunterKillerState.class, HunterKillerAction.class);

		// If nothing was specified, use some defaults
		if (botForPlayout == null)
			botForPlayout = new ShortCircuitRandomBot();
		playoutBot = botForPlayout;

		// Create the knowledge-base that we will be using
		kb = new KnowledgeBase();
		kb.put(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE, InfluenceMaps::calculateDistanceToEnemyStructures);

		// Instantiate the various strategies
		goal = roundCutoff(PLAYOUT_ROUND_CUTOFF);
		actionCompletion = new RandomActionCompletion();
		evaluation = evaluate(kb);
		playout = new NMCPlayout();
		sorting = new StaticSorting();
		gameLogic = new NMCGameLogic(sorting);

		// Adjust our name according to some settings, this will help during testing and/or watching replays
		this.botName = "NMC_" + playoutBot.getClass()
											.getSimpleName();
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public HunterKillerAction handle(HunterKillerState state) {
		Stopwatch actionTimer = new Stopwatch();
		actionTimer.start();

		// Check if we need to wait
		waitTimeBuffer();

		// Check if we want to update our knowledgebase
		if (state.getCurrentRound() <= KNOWLEDGEBASE_UPDATE_THRESHOLD_ROUND_NUMBER) {
			kb.update(state);
		}

		// Check that we can even issue any orders
		Map map = state.getMap();
		Player player = state.getActivePlayer();
		IntArray unitIDs = player.getUnitIDs();
		IntArray structureIDs = player.getStructureIDs();
		IntArray controlledIDs = new IntArray(unitIDs);
		// Only add structures that can spawn a unit
		for (int i = 0; i < structureIDs.size; i++) {
			Structure structure = (Structure) map.getObject(structureIDs.get(i));
			if (structure.canSpawnAUnit(state))
				controlledIDs.add(structureIDs.get(i));
		}
		// If we do not control any objects that can act, return an empty action
		if (controlledIDs.size == 0)
			return state.createNullMove();

		System.out.println(this.botName);
		System.out.println("Starting a NMC-search in round " + state.getCurrentRound());

		controlledIDs.shuffle();
		((StaticSorting) sorting).setStaticSorting(controlledIDs);

		// Create a fresh global CMAB
		IntMap<TreeSearchNode> cmab = new IntMap<TreeSearchNode>();

		// Construct a parent-search
		NaiveMonteCarloSearchBuilder builder = NMC.constructParentNMCSearch(playout,
																			evaluation,
																			EPSILON_PARENT_SEARCH,
																			EPSILON_CHILD_SEARCH,
																			cmab,
																			merger);
		builder.iterations(NMC_NUMBER_OF_ITERATIONS);

		// Create a new state to start the search from
		NMCState searchState = new NMCState(state.copy(), gameLogic.sorting);

		// Setup a search context
		val context = new SearchContext<Object, NMCState, Object, Object, Object>();
		context.source(searchState);
		context.search(builder.build());
		context.expansion(gameLogic);
		context.application(gameLogic);
		context.goal(gameLogic);

		// Make sure the root node is the special NMCRootNode
		context.startNode(new NaiveMonteCarloRootNode<NMCState, Object>(searchState.copy(), null));

		// Search for an action
		context.execute();

		// Check if the search was successful
		if (context.status() != Status.Success) {
			System.err.println("ERROR; search-context returned with status: " + context.status());
			// Return a random action
			return RandomBot.createRandomAction(state);
		}

		// Get the solution of the search
		HunterKillerAction action = new HunterKillerAction(state);
		CombinedAction solution = (CombinedAction) context.solution();
		for (HunterKillerOrder order : solution.orders) {
			action.addOrder(order);
		}

		long time = actionTimer.end();
		System.out.println("NMC returned with " + action.getOrders().size + " orders.");
		System.out.println("My action calculation time was " + TimeUnit.MILLISECONDS.convert(time, TimeUnit.NANOSECONDS) + " ms");
		System.out.println("");

		return action;
	}

	/**
	 * Goal strategy that cuts the search off after it has progressed for a specific number of rounds.
	 * 
	 * @param cutoffThreshold
	 *            The number of rounds after which to cut off the search.
	 * @return Whether or not the goal state is reached.
	 */
	public static GoalStrategy<Object, NMCState, CombinedAction, Object> roundCutoff(int cutoffThreshold) {
		return (context, state) -> {
			// Get the round for which we started the search
			int sourceRound = context.source().state.getCurrentRound();
			// We have reached our goal if the search has gone on for an amount of rounds >= the threshold, or if the
			// game has finished.
			return (state.state.getCurrentRound() - sourceRound) >= cutoffThreshold || state.state.isDone();
		};
	}

	/**
	 * Evaluates a state.
	 */
	public static StateEvaluation<NMCState, CombinedAction, TreeSearchNode<NMCState, PartialAction>> evaluate(KnowledgeBase kb) {
		return (context, node, state) -> {
			HunterKillerState gameState = state.state;
			// We evaluate states from our own perspective
			int rootPlayerID = context.source()
										.getPlayer();

			// Calculate how far along our farthest unit is to an enemy structure
			MatrixMap distanceMap = kb.get(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE)
										.getMap();
			// Evaluate the state
			float evaluation = HunterKillerStateEvaluation.evaluate(gameState,
																	rootPlayerID,
																	GAME_WIN_EVALUATION,
																	GAME_LOSS_EVALUATION,
																	distanceMap);

			// Reward evaluations that are further in the future less than earlier ones
			int playoutProgress = gameState.getCurrentRound() - context.source().state.getCurrentRound();
			float decay = HunterKillerStateEvaluation.calculateDecay(playoutProgress, PLAYOUT_ROUND_CUTOFF);

			return decay * evaluation;
		};
	}

	/**
	 * Core logic for applying the Naive Monte-Carlo Search strategy to HunterKiller.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	@AllArgsConstructor
	private class NMCGameLogic
			implements ExpansionStrategy<Object, NMCState, Object, Object>, ApplicationStrategy<Object, NMCState, Object, Object>,
			GoalStrategy<Object, NMCState, Object, Object> {

		/**
		 * The sorting used to determine which dimension (i.e. controlled object) to expand.
		 */
		public ControlledObjectSortingStrategy sorting;

		@Override
		public boolean done(SearchContext<Object, NMCState, Object, Object, ?> context, NMCState current) {
			return current.state.isDone();
		}

		@Override
		public NMCState apply(SearchContext<Object, NMCState, Object, Object, ?> context, NMCState state, Object action) {
			if (action instanceof PartialAction) {
				PartialAction pAction = (PartialAction) action;

				// Add the partial action into the combined action
				state.combinedAction.pushOrder(pAction);

				// Check if we still need to expand more dimensions
				if (!state.combinedAction.isComplete()) {

					// We can grab the next dimension from the partial action
					state.combinedAction.nextDimension = pAction.nextDimensionIndex;
					state.combinedAction.currentOrdering = pAction.currentOrdering;
					state.combinedAction.dimensions = pAction.currentOrdering.size;

				} else {
					state = applyCompleteAction(state);
				}
			} else if (action instanceof CombinedAction) {
				CombinedAction cAction = (CombinedAction) action;
				state.combinedAction = cAction;
				state = applyCompleteAction(state);
			}

			return state;
		}

		public NMCState applyCompleteAction(NMCState state) {
			do {
				// Create a HunterKillerAction from the CombinedAction
				HunterKillerAction hkAction = new HunterKillerAction(state.state);
				for (HunterKillerOrder order : state.combinedAction.orders) {
					hkAction.addOrder(order);
				}

				// Apply the created action to the hkState, so that it moves forward to the next player.
				rulesEngine.handle(state.state, hkAction);

				// Then for the next player, create a sorted unexpanded dimension set (clean HMCTSState)
				state = new NMCState(state.state, sorting);

				// Check if we have advanced into an empty Combined Action (no legal orders available)
				// Keep skipping and applying the rules until we get to a non-empty combined action.
			} while (state.combinedAction.isEmpty() && !state.state.isDone());
			return state;
		}

		/**
		 * Returns a collection of {@link PartialAction}s that is the result of expanding the next dimension in the
		 * provided state.
		 * 
		 * @param state
		 *            The state that should have its next dimension expanded.
		 */
		@Override
		public Iterable<? extends PartialAction> expand(SearchContext<Object, NMCState, Object, Object, ?> context, NMCState state) {
			Map map = state.state.getMap();

			// Post-process the ordering
			sorting.postProcess(state.combinedAction.currentOrdering, state.combinedAction.nextDimension);

			// The next dimension to expand can be found in the combined action
			int nextDimension = state.combinedAction.nextDimension;
			// Get the ID of the next object from the ordering that the combined action currently has
			int nextObjectID = state.combinedAction.currentOrdering.get(nextDimension);
			// Find the object on the map
			GameObject object = map.getObject(nextObjectID);

			if (object instanceof Unit) {
				Unit nextUnit = (Unit) object;

				// Generate all legal orders for this unit
				List<UnitOrder> orders = MoveGenerator.getAllLegalOrders(state.state, nextUnit);

				// Prune some orders we do not want to investigate
				BaseBot.filterFriendlyFire(orders, nextUnit, map);

				// Fill a collection of partial actions that encapsulate the possible unit-orders
				Array<PartialAction> partialActions = new Array<PartialAction>(false, orders.size());
				for (UnitOrder order : orders) {
					// Provide the partial actions with a link to the next dimension that should be expanded
					partialActions.add(new PartialAction(state.state.getCurrentPlayer(), order, nextDimension + 1,
															state.combinedAction.currentOrdering));
				}
				return partialActions;

			} else if (object instanceof Structure) {
				Structure nextStructure = (Structure) object;

				// Generate all legal order for this structure
				List<StructureOrder> orders = MoveGenerator.getAllLegalOrders(state.state, nextStructure);

				// Fill a collection of partial actions that encapsulate the possible structure-orders
				Array<PartialAction> partialActions = new Array<PartialAction>(false, orders.size());
				for (StructureOrder order : orders) {
					// Provide the partial actions with a link to the next dimension that should be expanded
					partialActions.add(new PartialAction(state.state.getCurrentPlayer(), order, nextDimension + 1,
															state.combinedAction.currentOrdering));
				}
				return partialActions;
			} else {
				throw new RuntimeException("Unknown type for expansion: " + object.getClass()
																					.getName());
			}
		}

	}

	private class NMCPlayout
			implements PlayoutStrategy<Object, NMCState, CombinedAction, Object> {

		@Override
		public NMCState playout(SearchContext<Object, NMCState, CombinedAction, Object, ?> context, NMCState state) {
			// Convert order so far into HunterKillerAction
			HunterKillerAction action = new HunterKillerAction(state.state);
			for (HunterKillerOrder order : state.combinedAction.orders) {
				action.addOrder(order);
			}

			// If we do not have an order for each dimension yet, use the ActionCompletionStrategy to generate them.
			if (!state.combinedAction.isComplete()) {
				Array<HunterKillerOrder> filledOrders = actionCompletion.fill(	state.state,
																				state.combinedAction.currentOrdering,
																				state.combinedAction.nextDimension);
				// Add the generated orders to the action
				for (HunterKillerOrder order : filledOrders) {
					action.addOrder(order);
				}

			}

			// Apply the created action on the HunterKillerState
			rulesEngine.handle(state.state, action);

			// Call the playout bot to continuously play actions until the goal is reached.
			while (!goal.done(context, state)) {
				HunterKillerAction botAction = playoutBot.handle(state.state);
				rulesEngine.handle(state.state, botAction);
			}

			return state;
		}

	}

	/**
	 * State representation for the Hierarchical MCTS implementation for HunterKiller. Holds a {@link HunterKillerState}
	 * and {@link CombinedAction}.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	@EqualsAndHashCode(callSuper = false)
	private class NMCState
			extends State {

		/**
		 * Game state.
		 */
		public HunterKillerState state;
		/**
		 * Contains orders for this state's active player.
		 */
		public CombinedAction combinedAction;

		/**
		 * Constructor.
		 * 
		 * @param state
		 *            The current game state.
		 * @param sorting
		 *            A sorting method to apply to the active player's controlled objects. See
		 *            {@link CombinedAction#CombinedAction(int, IntArray)}.
		 */
		public NMCState(HunterKillerState state, ControlledObjectSortingStrategy sorting) {
			this.state = state;
			this.combinedAction = new CombinedAction(state.getCurrentPlayer(), sorting.sort(state));
		}

		/**
		 * Copy constructor.
		 * 
		 * @param other
		 *            State to copy.
		 */
		public NMCState(NMCState other) {
			this.state = other.state.copy();
			this.combinedAction = other.combinedAction.copy();
		}

		@Override
		public long hashMethod() {
			return hashCode();
		}

		@SuppressWarnings("unchecked")
		@Override
		public NMCState copy() {
			return new NMCState(this);
		}

		@Override
		public int getPlayer() {
			return state.getCurrentPlayer();
		}

		@Override
		public int getPlayers() {
			return state.getNumberOfPlayers();
		}

	}

	/**
	 * Container for combining several {@link PartialAction}s during the Hierarchical MCTS.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	private class CombinedAction
			implements Move {

		/**
		 * The ID of the player that is the acting player for this action.
		 */
		public int player;
		/**
		 * The amount of dimensions (or units) that are represented in this action.
		 */
		public int dimensions;
		/**
		 * The orders for each object that have been assigned to them through the partial actions.
		 */
		public Array<HunterKillerOrder> orders;
		/**
		 * The next dimension (index of unit) to expand, -1 if we still need to determine an ordering.
		 */
		public int nextDimension = -1;
		/**
		 * Ordered array containing the IDs of the units for which a partial action should be created.
		 * Set by the last partial action we applied.
		 */
		public IntArray currentOrdering = null;

		/**
		 * Constructor.
		 * 
		 * @param player
		 *            of the player that is the acting player for this action.
		 * @param currentOrdering
		 *            Ordered array containing the IDs of the units for which a partial action should be created.
		 */
		public CombinedAction(int player, IntArray currentOrdering) {
			this.player = player;
			this.dimensions = currentOrdering.size;
			this.orders = new Array<HunterKillerOrder>(false, dimensions);
			this.nextDimension = 0;
			this.currentOrdering = currentOrdering;
		}

		/**
		 * Copy constructor.
		 * 
		 * @param other
		 *            The action to copy.
		 */
		public CombinedAction(CombinedAction other) {
			this.player = other.player;
			this.dimensions = other.dimensions;
			this.orders = new Array<HunterKillerOrder>(other.orders);
			this.nextDimension = other.nextDimension;
			this.currentOrdering = new IntArray(other.currentOrdering);
		}

		/**
		 * Whether there are no choices to be made for this combined action
		 */
		public boolean isEmpty() {
			return dimensions == 0;
		}

		/**
		 * Returns whether this CombinedAction can be executed or not.
		 */
		public boolean isComplete() {
			return dimensions <= orders.size;
		}

		/**
		 * Adds a {@link HunterKillerOrder} from a {@link PartialOrder} to this combined action.
		 * 
		 * @param action
		 *            The partial action from which the order should be added.
		 */
		public void pushOrder(PartialAction action) {
			orders.add(action.order);
		}

		/**
		 * Returns a deep copy of this action.
		 */
		public CombinedAction copy() {
			return new CombinedAction(this);
		}

		public String toString() {
			return "Ac; playerID " + player + " | dimensions " + dimensions + " | orders " + orders.size + " | nextD " + nextDimension
					+ " | ordering " + currentOrdering;
		}

		@Override
		public int getPlayer() {
			return player;
		}

	}

	/**
	 * Represents a {@link Move} in the Hierarchical MCTS setup. Contains an order for an object as well as the current
	 * ordering for its {@link CombinedAction} and the next dimension that should be expanded in that action.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	private class PartialAction
			implements Move {

		/**
		 * The ID of the player that is the acting player for this action.
		 */
		public int player;
		/**
		 * The order for the object.
		 */
		public HunterKillerOrder order;
		/**
		 * The index of the dimension that should be expanded next.
		 */
		public int nextDimensionIndex;
		/**
		 * Ordered array containing the IDs of the objects for which a partial action should be created.
		 */
		public IntArray currentOrdering;

		/**
		 * Constructor.
		 * 
		 * @param player
		 *            The ID of the player that is the acting player for this action.
		 * @param order
		 *            The order for the object.
		 * @param nextDimensionIndex
		 *            The index of the dimension that should be expanded next.
		 * @param currentOrdering
		 *            Ordered array containing the IDs of the units for which a partial action should be created.
		 */
		public PartialAction(int player, HunterKillerOrder order, int nextDimensionIndex, IntArray currentOrdering) {
			this.player = player;
			this.order = order;
			this.nextDimensionIndex = nextDimensionIndex;
			this.currentOrdering = new IntArray(currentOrdering);
		}

		public String toString() {
			return "Ap; playerID " + player + " | order " + order + " | nextIndex " + nextDimensionIndex + " | ordering " + currentOrdering;
		}

		@Override
		public int getPlayer() {
			return player;
		}

	}

}
