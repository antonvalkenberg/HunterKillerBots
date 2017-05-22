package net.codepoke.ai.challenges.hunterkiller.bots;

import java.util.Random;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.val;
import net.codepoke.lib.util.ai.SearchContext;
import net.codepoke.lib.util.ai.State;
import net.codepoke.lib.util.ai.game.Move;
import net.codepoke.lib.util.ai.search.PlayoutStrategy;
import net.codepoke.lib.util.ai.search.SearchStrategy;
import net.codepoke.lib.util.ai.search.SolutionStrategy;
import net.codepoke.lib.util.ai.search.StateEvaluation;
import net.codepoke.lib.util.ai.search.tree.TreeExpansion;
import net.codepoke.lib.util.ai.search.tree.TreeSearchNode;
import net.codepoke.lib.util.ai.search.tree.TreeSelection;
import net.codepoke.lib.util.ai.search.tree.nmc.CompositeTreeSearchNode;
import net.codepoke.lib.util.ai.search.tree.nmc.NaiveMonteCarloRootNode;
import net.codepoke.lib.util.ai.search.tree.nmc.NaiveMonteCarloSearch;
import net.codepoke.lib.util.ai.search.tree.nmc.NaiveMonteCarloSearch.NaiveMonteCarloSearchBuilder;
import net.codepoke.lib.util.datastructures.random.MersenneTwister;
import net.codepoke.lib.util.functions.Function2;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntMap;

@Data
@AllArgsConstructor
public class NMC<Domain, Position, Action, Subject, Solution>
		implements SearchStrategy<Domain, Position, Action, Subject, Solution> {

	public static final Random rng = new MersenneTwister();

	public static final int CHILD_SEARCH_MINIMUM_VISITS_BEFORE_NODE_EVALUATION = 0;
	public static final int CHILD_SEARCH_MINIMUM_VISITS_BEFORE_TREE_EXPANSION = 0;
	public static final int CHILD_SEARCH_ITERATIONS = 0;

	PlayoutStrategy<Domain, Position, Action, Subject> playout;

	StateEvaluation<Position, Action, TreeSearchNode<Position, Action>> evaluation;

	float epsilonParentSearch;

	float epsilonChildSearch;

	IntMap<TreeSearchNode<Position, Action>> cmab;

	Function2<Action, Position, Array<TreeSearchNode<Position, Action>>> merger;

	@Override
	public void search(SearchContext<Domain, Position, Action, Subject, Solution> objective) {
		// TODO: create the search logic
	}

	public NaiveMonteCarloSearchBuilder<Object, ? extends State, ? extends Move, Object, Object> constructParentNMCSearch(
			PlayoutStrategy playout, StateEvaluation evaluation, float epsilonParent, float epsilonChild, IntMap<TreeSearchNode> cmab,
			Function2<? extends Move, ? super State, ? super Array<TreeSearchNode>> merger) {

		NaiveMonteCarloSearchBuilder<Object, ? extends State, ? extends Move, Object, Object> search = NaiveMonteCarloSearch.builder();
		search.exploration((context, value) -> {
			return (rng.nextFloat() > epsilonParent);
		});
		search.solution(SolutionStrategy.Util.SOLUTION_ACTION);
		search.playout(playout);
		search.evaluation(evaluation);

		// Expansion: conduct a search for each individual sub action, then combine and merge
		search.expansion((parentContext, parentNode, oldState) -> {
			int dimension = 0;
			State state = parentContext.cloner()
										.clone(oldState);
			int currentPlayer = state.getPlayer();
			Array<TreeSearchNode> movesPerDimension = new Array<TreeSearchNode>();

			// Each expansion, clear previous information and disable reports
			SearchContext context = parentContext.copy();
			context.clearResetters();
			context.constructReport(false);

			// Keep creating action until we change player
			while (state.getPlayer() == currentPlayer && !context.goal()
																	.done(context, state)) {

				// Find the action for this submove
				context.search(constructChildNMCSearch(epsilonChild));
				context.source(state);
				context.startNode(cmab.get(dimension));
				context.execute();

				// Apply the action found and store the parent
				TreeSearchNode node = (TreeSearchNode) context.solution();
				cmab.put(dimension, (TreeSearchNode) node.getParent());
				movesPerDimension.add(node);

				state = (State) context.application()
										.apply(context, state, node.getPayload());

				dimension++;
			}

			// Create a node that holds all search nodes
			CompositeTreeSearchNode node = new CompositeTreeSearchNode(merger.apply(oldState, movesPerDimension));
			val action = node.getPayload();
			node.setSubMovesMade(movesPerDimension);

			node.setHash(action.hashCode());

			val rootNode = (NaiveMonteCarloRootNode<State, Move>) parentNode;
			val oldNode = rootNode.getNodeSet()
									.get(node.getHash());

			if (oldNode != null) {
				return oldNode;
			} else {
				rootNode.addChild(node);
				node.setParent(rootNode);
				return node;
			}
		});

		// Backpropagation : Update the CMAB worth, and the individual MAB node worth as well.
		// Handled by the CompositeTreeSearchNode
		return search;
	}

	public NaiveMonteCarloSearch<Object, ? extends State, ? extends Move, Object, Object> constructChildNMCSearch(float epsilon) {
		// Set up the builder for the search
		NaiveMonteCarloSearchBuilder<Object, ? extends State, ? extends Move, Object, Object> builder = NaiveMonteCarloSearch.builder();

		// Create the selection and expansion strategies that are used in the final-selection strategy

		// Select the node with the best ratio of score to visits
		TreeSelection<State, Move> selection = TreeSelection.Util.selectBestNode(node -> {
			if (node.getVisits() == 0)
				return 0;
			else
				return node.getScore() / node.getVisits();
		}, CHILD_SEARCH_MINIMUM_VISITS_BEFORE_NODE_EVALUATION);
		// Expansion can be done at any time
		TreeExpansion<State, Move> expansion = TreeExpansion.Util.<State, Move> createMinimumTExpansion(CHILD_SEARCH_MINIMUM_VISITS_BEFORE_TREE_EXPANSION);

		// Set the final selection strategy
		builder.finalSelection((context, root) -> {

			if (root.getChildren().size == 0 || rng.nextFloat() > epsilon) {
				// Exploration: expand into a new action
				TreeSearchNode<State, Move> node = expansion.expand(context, root, context.source());

				if (node.getPayload() != null)
					return node;
				// If no new action is found, go to Exploitation
			}

			// Exploitation: select a node from the root
			TreeSearchNode<State, Move> nextSelectedNode = selection.selectNextNode(context, root);
			if (nextSelectedNode == null)
				nextSelectedNode = selection.selectNextNode(context, root);

			return nextSelectedNode;
		});

		// The child-search is going to return a single node as solution
		builder.solution(SolutionStrategy.Util.SOLUTION_NODE);

		// The child-search returns to the parent after each iteration
		builder.iterations(CHILD_SEARCH_ITERATIONS);

		// These next strategies are ignored for the child-search
		builder.playout((context, state) -> {
			return state;
		});
		builder.evaluation((context, node, state) -> {
			return 0;
		});
		builder.backPropagation((context, evaluation, node, state) -> {

		});

		// Return the correctly setup builder
		return builder.build();
	}

}
