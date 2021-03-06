package net.codepoke.ai.challenges.hunterkiller.bots;

import java.util.ArrayList;
import java.util.HashSet;
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
import net.codepoke.ai.challenge.hunterkiller.gameobjects.GameObject;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.unit.Unit;
import net.codepoke.ai.challenge.hunterkiller.orders.HunterKillerOrder;
import net.codepoke.ai.challenge.hunterkiller.orders.StructureOrder;
import net.codepoke.ai.challenge.hunterkiller.orders.UnitOrder;
import net.codepoke.ai.challenges.hunterkiller.InfluenceMaps;
import net.codepoke.ai.challenges.hunterkiller.InfluenceMaps.KnowledgeBase;
import net.codepoke.ai.challenges.hunterkiller.bots.HMCTSBot.RandomActionCompletion;
import net.codepoke.ai.challenges.hunterkiller.bots.LSIBot.CombinedAction;
import net.codepoke.ai.challenges.hunterkiller.bots.LSIBot.LSIState;
import net.codepoke.ai.challenges.hunterkiller.bots.evaluation.HunterKillerStateEvaluation;
import net.codepoke.ai.challenges.hunterkiller.bots.sorting.ControlledObjectSortingStrategy;
import net.codepoke.ai.challenges.hunterkiller.bots.sorting.RandomSorting;
import net.codepoke.lib.util.ai.SearchContext;
import net.codepoke.lib.util.ai.SearchContext.Status;
import net.codepoke.lib.util.ai.State;
import net.codepoke.lib.util.ai.game.Move;
import net.codepoke.lib.util.ai.search.ApplicationStrategy;
import net.codepoke.lib.util.ai.search.EvaluationStrategy;
import net.codepoke.lib.util.ai.search.GoalStrategy;
import net.codepoke.lib.util.ai.search.PlayoutStrategy;
import net.codepoke.lib.util.ai.search.SearchStrategy;
import net.codepoke.lib.util.common.Stopwatch;
import net.codepoke.lib.util.datastructures.MatrixMap;
import net.codepoke.lib.util.datastructures.random.OddmentTable;
import net.codepoke.lib.util.datastructures.tuples.Pair;

import org.apache.commons.math3.util.FastMath;
import org.eclipse.xtext.xbase.lib.Functions.Function2;
import org.eclipse.xtext.xbase.lib.Functions.Function3;
import org.eclipse.xtext.xbase.lib.IterableExtensions;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntArray;

/**
 * A bot that uses Linear Side-Information Search to create HunterKillerOrders.
 * It divides its calculation time between sampling actions to generate information on them, and simulating.
 * 
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
public class LSIBot
		extends BaseBot<HunterKillerState, HunterKillerAction>
		implements SearchStrategy<Object, LSIState, CombinedAction, Object, HunterKillerAction> {

	/**
	 * Unique identifier, supplied by the AI-Competition for HunterKiller.
	 */
	private static final String myUID = "";
	/**
	 * Name of this bot as it is registered to the AI-Competition for HunterKiller.
	 */
	@Getter
	public String botName = "LSIBot";

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

	/**
	 * The sorting used to determine which dimension (i.e. controlled object) to sample.
	 */
	public ControlledObjectSortingStrategy sorting;
	/**
	 * A random way of completing an action during a LSI-playout.
	 */
	private RandomActionCompletion randomCompletion;
	/**
	 * Bot that can be called to simulate actions during a LSI-playout.
	 */
	private BaseBot<HunterKillerState, HunterKillerAction> playoutBot;
	/**
	 * Number of rounds after which a playout is cut off.
	 */
	private static final int PLAYOUT_ROUND_CUTOFF = 20;

	/** These numbers indicate the reward that is used in the evaluation function for a game win or loss. */
	private static final int GAME_WIN_EVALUATION = 100000;
	private static final int GAME_LOSS_EVALUATION = -1 * GAME_WIN_EVALUATION;

	/**
	 * Amount of samples used for generating the side-information.
	 */
	private static final int SAMPLES_FOR_GENERATION = 250;
	/**
	 * Amount of samples used for evaluating the generated information.
	 */
	private static final int SAMPLES_FOR_EVALUATION = 750;
	/**
	 * The factor by which to adjust the amount of evaluation samples.
	 * This factor is needed because LSI uses more iterations than allocated.
	 */
	private static final double SAMPLES_EVALUATION_ADJUSTMENT_FACTOR = .5;

	/** These variables are for keeping track of the number of simulations LSI uses for each step. */
	private int samplesGeneration = SAMPLES_FOR_GENERATION;
	private int samplesEvaluation = SAMPLES_FOR_EVALUATION;
	private int simulationsGeneration = 0;
	private int simulationsEvaluation = 0;

	/** Transforms from a combined-action to the solution. */
	private Function2<SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction>, CombinedAction, HunterKillerAction> solutionStrategy = ((Function2<SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction>, CombinedAction, HunterKillerAction>) (
			SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction> context, CombinedAction action) -> {
		// Create an action for the state that we started the search for
		HunterKillerState orgState = context.source().state;
		HunterKillerAction solution = new HunterKillerAction(orgState);

		// Add all orders in the combined action to the solution
		for (HunterKillerOrder order : action.orders) {
			solution.addOrder(order);
		}

		return solution;
	});

	/** Creates a combined action from a targeted partial action. */
	private Function3<SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction>, LSIState, PartialAction, CombinedAction> extendMove = ((Function3<SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction>, LSIState, PartialAction, CombinedAction>) (
			SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction> context, LSIState state, PartialAction order) -> {
		// Create a combined action for the current state
		CombinedAction action = new CombinedAction(state.state.getCurrentPlayer(), sorting.sort(state.state));
		// Add partial action
		action.pushOrder(order);
		// Remove the order's object-ID from the current ordering
		IntArray currentOrdering = action.currentOrdering;
		currentOrdering.removeValue(order.order.objectID);
		// Call action completion strategy with the remaining IDs in the current ordering
		Array<HunterKillerOrder> filledOrders = randomCompletion.fill(state.state, currentOrdering);
		for (HunterKillerOrder filledOrder : filledOrders) {
			action.pushOrder(filledOrder);
		}
		return action;
	});

	/** Given a list of distributions which generate a partial action, create a combined action */
	private Function3<SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction>, LSIState, List<OddmentTable<PartialAction>>, CombinedAction> sampleMove = ((Function3<SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction>, LSIState, List<OddmentTable<PartialAction>>, CombinedAction>) (
			SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction> context, LSIState state,
			List<OddmentTable<PartialAction>> orderDistributions) -> {
		// Create a combined action, ordering is not relevant
		CombinedAction action = new CombinedAction(state.state.getCurrentPlayer(), sorting.sort(state.state));
		// For each oddmentTable, call next() and push to combined action
		for (OddmentTable<PartialAction> orderDistribution : orderDistributions) {
			action.pushOrder(orderDistribution.next());
		}
		return action;
	});

	/** Returns the number of dimensions that exist. */
	private Function2<SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction>, LSIState, Integer> dimensions = ((Function2<SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction>, LSIState, Integer>) (
			SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction> context, LSIState state) -> {
		// Get the number of dimensions from the combined action for this state
		return state.combinedAction.dimensions;
	});

	/** Returns all actions for a given dimension. */
	private Function3<SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction>, LSIState, Integer, Iterable<PartialAction>> actions = ((Function3<SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction>, LSIState, Integer, Iterable<PartialAction>>) (
			SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction> context, LSIState state, Integer dimension) -> {
		Map map = state.state.getMap();
		// Get the ID of the next object from the ordering that the combined action currently has
		int nextObjectID = state.combinedAction.currentOrdering.get(dimension);
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
				partialActions.add(new PartialAction(state.state.getCurrentPlayer(), order));
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
				partialActions.add(new PartialAction(state.state.getCurrentPlayer(), order));
			}
			return partialActions;
		} else {
			throw new RuntimeException("Unknown type for expansion: " + object.getClass()
																				.getName());
		}
	});

	GoalStrategy<Object, LSIState, CombinedAction, Object> goal;
	PlayoutStrategy<Object, LSIState, CombinedAction, Object> playout;
	ApplicationStrategy<Object, LSIState, CombinedAction, Object> application;
	EvaluationStrategy<Object, LSIState, CombinedAction, Object> evaluation;

	public LSIBot() {
		this(null);
	}

	public LSIBot(BaseBot<HunterKillerState, HunterKillerAction> botForPlayout) {
		super(myUID, HunterKillerState.class, HunterKillerAction.class);

		// If nothing was specified, use some defaults
		if (botForPlayout == null)
			botForPlayout = new ShortCircuitRandomBot();

		// Create the knowledge-base that we will be using
		kb = new KnowledgeBase();
		kb.put(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE, InfluenceMaps::calculateDistanceToEnemyStructures);

		playoutBot = botForPlayout;

		// Create the utility classes that LSI needs access to
		sorting = new RandomSorting();
		randomCompletion = new RandomActionCompletion();
		goal = roundCutoff(PLAYOUT_ROUND_CUTOFF);
		playout = new LSIPlayoutStrategy(playoutBot, goal);
		application = new LSIApplicationStrategy();
		evaluation = evaluate(kb);

		// Adjust our name according to some settings, this will help during testing and/or watching replays
		this.botName = "LSI_" + playoutBot.getClass()
											.getSimpleName();
	}

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
		if (state.getActivePlayer()
					.getUnitIDs().size == 0) {
			boolean canDoSomething = false;
			for (Structure structure : state.getActivePlayer()
											.getStructures(state.getMap())) {
				if (structure.canSpawnAUnit(state))
					canDoSomething = true;
			}
			if (!canDoSomething)
				return state.createNullMove();
		}

		System.out.println(this.botName);
		System.out.println("Starting an LSI search in round " + state.getCurrentRound());

		// We are going to use a special state as root for the search, so that we can keep track of all selected
		// orders
		LSIState searchState = new LSIState(state.copy(), sorting);

		// Setup a search with the search-state as source
		val context = SearchContext.context(null, searchState, null, null, this, null);

		// Search for an action
		context.execute();

		// Check if the search was successful
		if (context.status() != Status.Success) {
			System.err.println("ERROR; search-context returned with status: " + context.status());
			// Return a random action
			return RandomBot.createRandomAction(state);
		}

		// Get the solution of the search
		HunterKillerAction action = context.solution();

		long time = actionTimer.end();
		System.out.println("LSI returned with " + action.getOrders().size + " orders.");
		System.out.println("My action calculation time was " + TimeUnit.MILLISECONDS.convert(time, TimeUnit.NANOSECONDS) + " ms");
		System.out.println("");

		return action;
	}

	@Override
	public void search(SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction> context) {
		// Reset the counters for checking how many iterations we do
		simulationsGeneration = 0;
		simulationsEvaluation = 0;
		// Adjust the amount of allowed evaluations, because LSI uses more than it is awarded.
		// The factor used here is empirically determined.
		int oldSamplesEvaluation = this.samplesEvaluation;
		this.samplesEvaluation = (int) (this.samplesEvaluation * SAMPLES_EVALUATION_ADJUSTMENT_FACTOR);

		// LSI is divided up into two strategies:
		// - Generate the appropriate subset of C, C* from which we can select actions.
		final HashSet<CombinedAction> subsetActions = this.generate(context, this.samplesGeneration, this.samplesEvaluation);
		// - Evaluate the best combined action in C*.
		final CombinedAction bestAction = this.evaluate(context, this.samplesEvaluation, subsetActions);

		// Add the best action to the context's solution and mark our search as successful
		context.solution(this.solutionStrategy.apply(context, bestAction));
		context.status(Status.Success);

		System.out.println("In round " + context.source().state.getCurrentRound() + " LSI used " + simulationsGeneration
							+ " sims for generation and " + simulationsEvaluation + " sims for evaluation.");

		// Set the evaluation samples back to its original amount
		this.samplesEvaluation = oldSamplesEvaluation;
	}

	/**
	 * Generates the interesting subset of actions C* from C.
	 * 
	 * 1) Generate a weight function R^ from PartialActions (adopting the linear side information assumption).
	 * 2) Schematically generating a probability distribution D_R^ over CombinedAction space C, biased "towards" R^.
	 * 3) Sample a number of CombinedActions C* from D_R^.
	 * 
	 * @param context
	 *            The current search context.
	 * @param samplesGeneration
	 *            The number of simulations allowed in the generation step.
	 * @param samplesEvaluation
	 *            The number of simulations allowed in the evaluation step.
	 */
	public HashSet<CombinedAction> generate(final SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction> context,
			final int samplesGeneration, final int samplesEvaluation) {
		// Create the side information using the allowed number of generation simulations
		final ArrayList<OddmentTable<PartialAction>> weightActions = this.sideInfo(context, samplesGeneration);

		// Create combined actions by sampling a move from the side information
		final HashSet<CombinedAction> subsetActions = new HashSet<CombinedAction>();
		for (int i = 0; i < samplesEvaluation; i++) {
			subsetActions.add(this.sampleMove.apply(context, context.source(), weightActions));
		}

		return subsetActions;
	}

	/**
	 * Produces the side info, a list of distributions for individual actions in dimensions to an average score.
	 * 
	 * @param context
	 *            The current search context.
	 * @param samplesGeneration
	 *            The number of simulations to run while creating the side info.
	 */
	public ArrayList<OddmentTable<PartialAction>> sideInfo(
			final SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction> context, final int samplesGeneration) {
		// Determine the amount of objects that can be sent an order in this state.
		final Integer numberOfDimensions = this.dimensions.apply(context, context.source());
		final ArrayList<OddmentTable<PartialAction>> distributionCombined = new ArrayList<OddmentTable<PartialAction>>(numberOfDimensions);

		// How many simulations can be used per dimension
		final int samplesPerDimension = Math.max(1, (int) Math.floor(samplesGeneration / numberOfDimensions));

		// Go through each dimension
		for (int i = 0; i < numberOfDimensions; i++) {
			// Create a new distribution table for this dimension
			final OddmentTable<PartialAction> distributionDimension = new OddmentTable<PartialAction>();

			// Generate all possible actions for this dimension
			Iterable<PartialAction> actions = this.actions.apply(context, context.source(), i);
			// Determine the amount of simulations per action
			int samplesPerAction = (int) Math.max(1, Math.floor(samplesPerDimension / IterableExtensions.size(actions)));
			// Go through all the actions
			for (final PartialAction action : actions) {
				float value = 0f;
				for (int j = 0; j < samplesPerAction; j++) {
					// Extend this partial action into a combined action
					final CombinedAction combined = this.extendMove.apply(context, context.source(), action);

					// Increase the value of this action with the reward from the playout
					value += this.playout(context, combined);

					// Keep track of how many simulations we run
					this.simulationsGeneration++;
				}
				// Add the average value of this action
				distributionDimension.add(value / samplesPerAction, action, false);
			}

			// Add the distribution for this action (R) to the list of distributions (R^)
			distributionCombined.add(distributionDimension);
		}

		// Recalculate the distribution for each OddmentTable
		for (final OddmentTable<PartialAction> distro : distributionCombined) {
			distro.recalculate();
		}

		return distributionCombined;
	}

	/**
	 * Simulates a single combined action.
	 * 
	 * @param context
	 *            The current search context.
	 * @param action
	 *            The combined action that should be evaluated.
	 * @return The value of the end state according to the evaluation function.
	 */
	public double playout(final SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction> context,
			final CombinedAction action) {
		// Copy the state so we do not contaminate it
		LSIState state = context.cloner()
								.clone(context.source());
		// Apply the action to the state
		state = this.application.apply(context, state, action);
		// Playout the game
		state = this.playout.playout(context, state);
		// Evaluate the end state and return the value
		return this.evaluation.evaluate(context, context.source(), action, state);
	}

	/**
	 * Represents a strategy that applies a {@link CombinedAction} to a {@link LSIState}.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	private class LSIApplicationStrategy
			implements ApplicationStrategy<Object, LSIState, CombinedAction, Object> {

		@Override
		public LSIState apply(SearchContext<Object, LSIState, CombinedAction, Object, ?> context, LSIState state, CombinedAction action) {
			do {
				// Create a HunterKillerAction from the CombinedAction
				HunterKillerAction hkAction = new HunterKillerAction(state.state);
				for (HunterKillerOrder order : action.orders) {
					hkAction.addOrder(order);
				}

				// Apply the created action to the hkState, so that it moves forward to the next player.
				rulesEngine.handle(state.state, hkAction);

				// Then for the next player, create a sorted unexpanded dimension set (clean LSIState)
				state = new LSIState(state.state, sorting);

				// Check if we have advanced into an empty Combined Action (no legal orders available)
				// Keep skipping and applying the rules until we get to a non-empty combined action.
			} while (state.combinedAction.isEmpty() && !state.state.isDone());

			return state;
		}

	}

	/**
	 * Represents a strategy that plays out a game of HunterKiller starting from a specific {@link LSIState}.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	@AllArgsConstructor
	private class LSIPlayoutStrategy
			implements PlayoutStrategy<Object, LSIState, CombinedAction, Object> {

		/**
		 * Bot for HunterKiller that generates a {@link HunterKillerAction} to be used during the playout.
		 */
		BaseBot<HunterKillerState, HunterKillerAction> playoutBot;
		/**
		 * Determines when a playout is finished.
		 */
		GoalStrategy<Object, LSIState, CombinedAction, Object> goal;

		@Override
		public LSIState playout(SearchContext<Object, LSIState, CombinedAction, Object, ?> context, LSIState state) {
			// Call the playout bot to continuously play actions until the goal is reached.
			while (!goal.done(context, state)) {
				HunterKillerAction botAction = playoutBot.handle(state.state);
				rulesEngine.handle(state.state, botAction);
			}

			return state;
		}

	}

	/**
	 * Goal strategy that cuts the search off after it has progressed for a specific number of rounds.
	 * 
	 * @param cutoffThreshold
	 *            The number of rounds after which to cut off the search.
	 * @return Whether or not the goal state is reached.
	 */
	public static GoalStrategy<Object, LSIState, CombinedAction, Object> roundCutoff(int cutoffThreshold) {
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
	public static EvaluationStrategy<Object, LSIState, CombinedAction, Object> evaluate(KnowledgeBase kb) {
		return (context, source, action, state) -> {
			HunterKillerState gameState = state.state;
			// NOTE: This entire method is written from the root player's perspective
			int rootPlayerID = source.getPlayer();

			// Calculate how far along our farthest unit is to an enemy structure
			MatrixMap distanceMap = kb.get(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE)
										.getMap();
			// Evaluate the state
			double evaluation = HunterKillerStateEvaluation.evaluate(gameState,
																	rootPlayerID,
																	GAME_WIN_EVALUATION,
																	GAME_LOSS_EVALUATION,
																	distanceMap);

			// Reward evaluations that are further in the future less than earlier ones
			int playoutProgress = gameState.getCurrentRound() - context.source().state.getCurrentRound();
			double decay = HunterKillerStateEvaluation.calculateDecay(playoutProgress, PLAYOUT_ROUND_CUTOFF);

			return decay * evaluation;
		};
	}

	/**
	 * Select the best combined action from C*.
	 * 
	 * @param context
	 *            The current search context.
	 * @param samplesEvaluation
	 *            The number of simulations allowed in the evaluation step.
	 * @param possibleActions
	 *            The set of CombinedActions generated in the generation step.
	 */
	public CombinedAction evaluate(final SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction> context,
			final int samplesEvaluation, final HashSet<CombinedAction> possibleActions) {

		// Create a list of Pairs, with a value for each CombinedAction.
		ArrayList<Pair<CombinedAction, Float>> currentActions = new ArrayList<Pair<CombinedAction, Float>>();
		for (final CombinedAction action : possibleActions) {
			currentActions.add(Pair.<CombinedAction, Float> t(action, 0f));
		}

		// Define the function that will go through all actions, evaluate them and return the best half
		final Function3<ArrayList<Pair<CombinedAction, Float>>, Integer, Integer, ArrayList<Pair<CombinedAction, Float>>> iteration = (
				ArrayList<Pair<CombinedAction, Float>> actions, Integer numberActions, Integer simulations) -> {

			// Create a list for the actions we handle this round
			final ArrayList<Pair<CombinedAction, Float>> actionsThisRound = new ArrayList<Pair<CombinedAction, Float>>();
			actionsThisRound.addAll(actions);

			// Calculate how many simulations can be spent on an action this round
			int simulationsPerAction = (int) Math.max(1, Math.floor(simulations / actions.size()));

			for (int i = 0; i < actionsThisRound.size(); i++) {
				final Pair<CombinedAction, Float> action = actionsThisRound.get(i);

				// Maintain a value for this action
				float value = 0f;
				// Simulate this action
				for (int j = 0; j < simulationsPerAction; j++) {
					value += this.playout(context, action.getX());
					this.simulationsEvaluation++;
				}

				// Update the value for this action
				action.setY(action.getY() + value);
			}

			// Determine the amount of actions for the next round
			final int actionsNewRound = (int) Math.max(1, Math.ceil(actions.size() / 2));

			return actionsThisRound.stream()
									.sorted((Pair<CombinedAction, Float> a, Pair<CombinedAction, Float> b) -> {
										// Sort by descending values
										return Float.compare(b.getY(), a.getY());
									})
									// Only take the amount of actions for the next round
									.limit(actionsNewRound)
									// Create an ArrayList to return
									.collect(() -> {
										// Supplier
										return new ArrayList<Pair<CombinedAction, Float>>((actionsNewRound + 1));
									},
												(ArrayList<Pair<CombinedAction, Float>> a, Pair<CombinedAction, Float> b) -> {
													// Accumulator
													a.add(b);
												},
												(ArrayList<Pair<CombinedAction, Float>> a, ArrayList<Pair<CombinedAction, Float>> b) -> {
													// Combiner
													a.addAll(b);
												});
		};

		// Determine the number of iterations of Sequential Halving we need
		final int numberActions = possibleActions.size();
		final int iterations = Math.max(1, (int) Math.floor(FastMath.log(2, numberActions)));
		for (int i = 0; i < iterations; i++) {
			// Calculate how many simulations can be spent on this iteration
			final int simulations = Math.max(	1,
												(int) Math.floor(samplesEvaluation
																	/ (currentActions.size() * Math.max(1,
																										Math.ceil(FastMath.log(	2,
																																numberActions))))));
			// Apply an iteration to the current list of actions
			currentActions = iteration.apply(currentActions, Integer.valueOf(numberActions), Integer.valueOf(Math.max(1, simulations)));
		}

		// Return the first action (because the list is sorted by descending value)
		if (currentActions.isEmpty())
			return null;
		return currentActions.get(0)
								.getX();
	}

	/**
	 * State representation for the LSI implementation for HunterKiller. Holds a {@link HunterKillerState} and a
	 * {@link CombinedAction}.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	@EqualsAndHashCode(callSuper = false)
	public class LSIState
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
		public LSIState(HunterKillerState state, ControlledObjectSortingStrategy sorting) {
			this.state = state;
			this.combinedAction = new CombinedAction(state.getCurrentPlayer(), sorting.sort(state));
		}

		/**
		 * Copy constructor.
		 * 
		 * @param other
		 *            State to copy.
		 */
		public LSIState(LSIState other) {
			this.state = other.state.copy();
			this.combinedAction = other.combinedAction.copy();
		}

		@Override
		public long hashMethod() {
			return hashCode();
		}

		@SuppressWarnings("unchecked")
		@Override
		public LSIState copy() {
			return new LSIState(this);
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
	 * Container for combining several {@link PartialAction}s during the LSI search.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	public class CombinedAction
			implements Move {

		/**
		 * The ID of the player that is the acting player for this action.
		 */
		public int player;
		/**
		 * Ordered array containing the IDs of the units for which a partial action should be created.
		 * Set by the last partial action we applied.
		 */
		public IntArray currentOrdering = null;
		/**
		 * The amount of dimensions (or units) that are represented in this action.
		 */
		public int dimensions;
		/**
		 * The orders for each object that have been assigned to them through the partial actions.
		 */
		public Array<HunterKillerOrder> orders;

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
			this.currentOrdering = currentOrdering;
			this.dimensions = currentOrdering.size;
			this.orders = new Array<HunterKillerOrder>(false, dimensions);
		}

		/**
		 * Copy constructor.
		 * 
		 * @param other
		 *            The action to copy.
		 */
		public CombinedAction(CombinedAction other) {
			this.player = other.player;
			this.currentOrdering = new IntArray(other.currentOrdering);
			this.dimensions = other.dimensions;
			this.orders = new Array<HunterKillerOrder>(other.orders);
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
		 * Adds a {@link HunterKillerOrder} to this combined action.
		 * 
		 * @param order
		 *            The order that should be added.
		 */
		public void pushOrder(HunterKillerOrder order) {
			orders.add(order);
		}

		/**
		 * Returns a deep copy of this action.
		 */
		public CombinedAction copy() {
			return new CombinedAction(this);
		}

		@Override
		public int getPlayer() {
			return player;
		}

		public String toString() {
			return "Ac; playerID " + player + " | dimensions " + dimensions + " | orders " + orders.size + " | ordering " + currentOrdering;
		}

	}

	/**
	 * Contains an order for an object.
	 * 
	 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
	 *
	 */
	private class PartialAction {

		/**
		 * The ID of the player that is the acting player for this action.
		 */
		public int player;
		/**
		 * The order for the object.
		 */
		public HunterKillerOrder order;

		/**
		 * Constructor.
		 * 
		 * @param player
		 *            The ID of the player that is the acting player for this action.
		 * @param order
		 *            The order for the object.
		 */
		public PartialAction(int player, HunterKillerOrder order) {
			this.player = player;
			this.order = order;
		}

		public String toString() {
			return "Ap; playerID " + player + " | order " + order;
		}

	}

}
