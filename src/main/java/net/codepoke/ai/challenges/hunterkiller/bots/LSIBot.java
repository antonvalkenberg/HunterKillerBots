package net.codepoke.ai.challenges.hunterkiller.bots;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

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
import net.codepoke.ai.challenges.hunterkiller.bots.HMCTSBot.RandomControlledObjectSorting;
import net.codepoke.ai.challenges.hunterkiller.bots.LSIBot.CombinedAction;
import net.codepoke.ai.challenges.hunterkiller.bots.LSIBot.LSIState;
import net.codepoke.lib.util.ai.SearchContext;
import net.codepoke.lib.util.ai.SearchContext.Status;
import net.codepoke.lib.util.ai.State;
import net.codepoke.lib.util.ai.game.Move;
import net.codepoke.lib.util.ai.search.EvaluationStrategy;
import net.codepoke.lib.util.ai.search.PlayoutStrategy;
import net.codepoke.lib.util.ai.search.SearchStrategy;
import net.codepoke.lib.util.common.Stopwatch;
import net.codepoke.lib.util.datastructures.random.OddmentTable;
import net.codepoke.lib.util.datastructures.tuples.Pair;

import org.apache.commons.math3.util.FastMath;
import org.eclipse.xtext.xbase.lib.ExclusiveRange;
import org.eclipse.xtext.xbase.lib.Functions.Function1;
import org.eclipse.xtext.xbase.lib.Functions.Function2;
import org.eclipse.xtext.xbase.lib.Functions.Function3;
import org.eclipse.xtext.xbase.lib.IterableExtensions;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntArray;

/**
 * A bot that uses Monte-Carlo Tree Search to create HunterKillerOrders.
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
	public final String botName = "LSIBot";

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
	 * The sorting used to determine which dimension (i.e. controlled object) to expand.
	 */
	public RandomControlledObjectSorting sorting;
	/**
	 * A random way of completing an action during a MCTS-playout.
	 */
	private RandomActionCompletion randomCompletion;

	private int samplesGeneration = 0;
	private int samplesEvaluation = 0;
	private int simulationsEvaluation = 0;
	private int simulationsGeneration = 0;

	/** The size of the subset we want to create, typically just the whole. */
	private Function1<Integer, Integer> subsetSize = ((Function1<Integer, Integer>) (Integer number) -> {
		return number;
	});

	/** Transforms from a c-action to the solution. */
	private Function2<SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction>, CombinedAction, HunterKillerAction> solutionStrategy = ((Function2<SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction>, CombinedAction, HunterKillerAction>) (
			SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction> context, CombinedAction action) -> {
		// Create an action for the state that we started the search for
		HunterKillerState orgState = context.source().state;
		HunterKillerAction solution = new HunterKillerAction(orgState);

		// Add all orders in the combined action to the solution
		for (HunterKillerOrder order : action.orders) {
			solution.addOrder(order);
		}

		// Could be that we haven't looked at all objects.
		Array<HunterKillerOrder> filledOrders = randomCompletion.fill(orgState, action.currentOrdering, action.nextDimension);
		for (HunterKillerOrder filledOrder : filledOrders) {
			solution.addOrder(filledOrder);
		}

		return solution;
	});

	/** Creates a full move from a targeted submove. */
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

	/** Given a list of distributions which generate a move, create a combined move */
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

	/** Returns the number of dimensions there exist. */
	private Function2<SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction>, LSIState, Integer> dimensions = ((Function2<SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction>, LSIState, Integer>) (
			SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction> context, LSIState state) -> {
		// Get the number of dimensions from the combined action for this state
		return state.combinedAction.dimensions;
	});

	/** Returns the number of actions for a given dimension. */
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

	private PlayoutStrategy<Object, LSIState, CombinedAction, Object> playout;

	private EvaluationStrategy<Object, LSIState, CombinedAction, Object> evaluation;

	public LSIBot() {
		super(myUID, HunterKillerState.class, HunterKillerAction.class);

		// Create the knowledge-base that we will be using
		kb = new KnowledgeBase();
		kb.put(KNOWLEDGE_LAYER_DISTANCE_TO_ENEMY_STRUCTURE, InfluenceMaps::calculateDistanceToEnemyStructures);

		// Create the utility classes that LSI needs access to
		randomCompletion = new RandomActionCompletion();
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
		System.out.println("My action calculation time was " + TimeUnit.SECONDS.convert(time, TimeUnit.NANOSECONDS) + " seconds");
		System.out.println("");

		return action;
	}

	@Override
	public void search(SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction> context) {
		// Reset our counters
		this.simulationsGeneration = 0;
		this.simulationsEvaluation = 0;
		final int oldSamplesEval = this.samplesEvaluation;
		int _samplesEvaluation = this.samplesEvaluation;
		// TODO does this still need to be cleaned up?
		this.samplesEvaluation = (_samplesEvaluation / 2);
		this.samplesEvaluation = ((int) (this.samplesEvaluation * 1.1));

		// LSI is divided up into two strategies:
		// - Generate the appropriate subset of C, C* from which we can select actions.
		final HashSet<CombinedAction> subsetActions = this.generate(context,
																	this.samplesGeneration,
																	(this.subsetSize.apply(Integer.valueOf(this.samplesEvaluation))).intValue());
		// - Evaluate the best c-action in C*.
		final CombinedAction bestAction = this.evaluate(context, this.samplesEvaluation, subsetActions);
		context.solution(this.solutionStrategy.apply(context, bestAction));
		this.samplesEvaluation = oldSamplesEval;
	}

	/**
	 * Generates the interesting subset of actions C* from C.
	 * 
	 * 1) Generate a weight function R^ from atomic actions (adopting the linear side information assumption).
	 * 2) Schematically generating a probability distribution D_R^ over c-action space C, biased "towards" R^.
	 * 3) Sample (up to) k(N_e) c-actions C* from D_R^.
	 * 
	 * @param samplesGeneration
	 *            , see N_g
	 * @param numberToGenerate
	 *            , see 3 (?)
	 * 
	 * @done
	 */
	@SuppressWarnings("unused")
	public HashSet<CombinedAction> generate(final SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction> context,
			final int samplesGeneration, final int numberToGenerate) {
		final ArrayList<OddmentTable<PartialAction>> weightActions = this.sideInfo(context, samplesGeneration);
		final HashSet<CombinedAction> subsetActions = new HashSet<CombinedAction>();
		ExclusiveRange _doubleDotLessThan = new ExclusiveRange(0, numberToGenerate, true);
		for (final Integer i : _doubleDotLessThan) {
			subsetActions.add(this.sampleMove.apply(context, context.source(), weightActions));
		}
		return subsetActions;
	}

	/**
	 * Select the best c-action from C*.
	 * 
	 * @param samplesEvaluation
	 *            , see N_e
	 * @param possibleActions
	 *            , see C
	 * 
	 * @done
	 */
	@SuppressWarnings("unused")
	public CombinedAction evaluate(final SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction> context,
			final int samplesEvaluation, final HashSet<CombinedAction> possibleActions) {
		ArrayList<Pair<CombinedAction, Float>> currentActions = new ArrayList<Pair<CombinedAction, Float>>();
		for (final CombinedAction action : possibleActions) {
			currentActions.add(Pair.<CombinedAction, Float> t(action, Float.valueOf(0f)));
		}
		final Function3<ArrayList<Pair<CombinedAction, Float>>, Integer, Integer, ArrayList<Pair<CombinedAction, Float>>> _function = (
				ArrayList<Pair<CombinedAction, Float>> actions, Integer numberActions, Integer simulations) -> {
			ArrayList<Pair<CombinedAction, Float>> _xblockexpression = null;
			{
				int _size = actions.size();
				int _divide = (_size / 2);
				double _max = Math.max(1, Math.ceil(_divide));
				final int actionsNewRound = ((int) _max);
				final ArrayList<Pair<CombinedAction, Float>> actionsThisRound = new ArrayList<Pair<CombinedAction, Float>>();
				actionsThisRound.addAll(actions);
				int _size_1 = actions.size();
				int _divide_1 = (simulations / _size_1);
				double _max_1 = Math.max(1, Math.floor(_divide_1));
				int simulationsPerAction = ((int) _max_1);
				int aIdx = 0;
				while (aIdx < actionsThisRound.size()) {
					{
						float value = 0f;
						final Pair<CombinedAction, Float> action_1 = actionsThisRound.get(aIdx);
						ExclusiveRange _doubleDotLessThan = new ExclusiveRange(0, simulationsPerAction, true);
						for (final Integer n : _doubleDotLessThan) {
							{
								float _value = value;
								float _playout = this.playout(context, action_1.getX());
								value = (_value + _playout);
								this.simulationsEvaluation++;
							}
						}
						Float _y = action_1.getY();
						float _plus = ((_y).floatValue() + value);
						action_1.setY(Float.valueOf(_plus));
						aIdx++;
					}
				}
				final Comparator<Pair<CombinedAction, Float>> _function_1 = (Pair<CombinedAction, Float> $0, Pair<CombinedAction, Float> $1) -> {
					return Float.compare(($1.getY()).floatValue(), ($0.getY()).floatValue());
				};
				final Supplier<ArrayList<Pair<CombinedAction, Float>>> _function_2 = () -> {
					return new ArrayList<Pair<CombinedAction, Float>>((actionsNewRound + 1));
				};
				final BiConsumer<ArrayList<Pair<CombinedAction, Float>>, Pair<CombinedAction, Float>> _function_3 = (
						ArrayList<Pair<CombinedAction, Float>> $0, Pair<CombinedAction, Float> $1) -> {
					$0.add($1);
				};
				final BiConsumer<ArrayList<Pair<CombinedAction, Float>>, ArrayList<Pair<CombinedAction, Float>>> _function_4 = (
						ArrayList<Pair<CombinedAction, Float>> $0, ArrayList<Pair<CombinedAction, Float>> $1) -> {
					$0.addAll($1);
				};
				_xblockexpression = actionsThisRound.stream()
													.sorted(_function_1)
													.limit(actionsNewRound)
													.<ArrayList<Pair<CombinedAction, Float>>> collect(_function_2, _function_3, _function_4);
			}
			return _xblockexpression;
		};
		final Function3<ArrayList<Pair<CombinedAction, Float>>, Integer, Integer, ArrayList<Pair<CombinedAction, Float>>> iteration = _function;
		final int numberActions = possibleActions.size();

		double _floor = Math.floor(FastMath.log(2, numberActions));
		final int iterations = Math.max(1, ((int) _floor));
		ExclusiveRange _doubleDotLessThan = new ExclusiveRange(0, iterations, true);
		for (final Integer i : _doubleDotLessThan) {
			{
				int _size = currentActions.size();
				double _max = Math.max(1, Math.ceil(FastMath.log(2, numberActions)));
				double _multiply = (_size * _max);
				double _divide = (samplesEvaluation / _multiply);
				double _floor_1 = Math.floor(_divide);
				final int simulations = Math.max(1, ((int) _floor_1));
				currentActions = iteration.apply(currentActions, Integer.valueOf(numberActions), Integer.valueOf(Math.max(1, simulations)));
			}
		}

		CombinedAction _xifexpression = null;
		int _size = currentActions.size();
		boolean _equals = (_size == 0);
		if (_equals) {
			_xifexpression = null;
		} else {
			_xifexpression = IterableExtensions.<Pair<CombinedAction, Float>> head(currentActions)
												.getX();
		}
		return _xifexpression;
	}

	/**
	 * Produces the side info, a list of distributions for individual actions in dimensions to an average score
	 */
	@SuppressWarnings("unused")
	public ArrayList<OddmentTable<PartialAction>> sideInfo(
			final SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction> context, final int samplesGeneration) {
		final Integer numberOfDimensions = this.dimensions.apply(context, context.source());
		final ArrayList<OddmentTable<PartialAction>> distributionCombined = new ArrayList<OddmentTable<PartialAction>>(
																														(numberOfDimensions).intValue());

		double _floor = Math.floor((samplesGeneration / (numberOfDimensions).intValue()));
		final int samplesPerDimension = Math.max(1, ((int) _floor));
		ExclusiveRange _doubleDotLessThan_2 = new ExclusiveRange(0, (numberOfDimensions).intValue(), true);
		for (final Integer dimension_2 : _doubleDotLessThan_2) {
			{
				final OddmentTable<PartialAction> distributionDimension = new OddmentTable<PartialAction>();
				Iterable<PartialAction> actions = this.actions.apply(context, context.source(), dimension_2);
				int _size = IterableExtensions.size(actions);
				int _divide = (samplesPerDimension / _size);
				double _max = Math.max(1, Math.floor(_divide));
				int samplesPerAction = ((int) _max);
				for (final PartialAction action : actions) {
					{
						float value = 0f;
						ExclusiveRange _doubleDotLessThan_3 = new ExclusiveRange(0, samplesPerAction, true);
						for (final Integer m : _doubleDotLessThan_3) {
							{
								final CombinedAction combined = this.extendMove.apply(context, context.source(), action);
								float _value = value;
								float _playout = this.playout(context, combined);
								value = (_value + _playout);
								this.simulationsGeneration++;
							}
						}
						distributionDimension.add((value / samplesGeneration), action, false);
					}
				}
				distributionCombined.add(distributionDimension);
			}
		}
		for (final OddmentTable<PartialAction> distro : distributionCombined) {
			distro.recalculate();
		}
		return distributionCombined;
	}

	/**
	 * Simulates a single combined action.
	 */
	public float playout(final SearchContext<Object, LSIState, CombinedAction, Object, HunterKillerAction> context,
			final CombinedAction action) {
		float _xblockexpression = (float) 0;
		{
			final LSIState state = context.cloner()
											.clone(context.source());
			// TODO implement application correctly
			context.application()
					.apply(context, state, action);
			// TODO implement playout
			this.playout.playout(context, state);
			// TODO implement evaluation
			_xblockexpression = this.evaluation.evaluate(context, context.source(), action, state);
		}
		return _xblockexpression;
	}

	/**
	 * State representation for the LSI implementation for HunterKiller. Holds a {@link HunterKillerState} and
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
		public LSIState(HunterKillerState state, RandomControlledObjectSorting sorting) {
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
			return "Ac; playerID " + player + " | dimensions " + dimensions + " | orders " + orders.size + " | nextD " + nextDimension
					+ " | ordering " + currentOrdering;
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