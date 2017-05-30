package net.codepoke.ai.challenges.hunterkiller.bots;

import java.util.Random;

import lombok.val;
import net.codepoke.lib.util.ai.SearchContext;
import net.codepoke.lib.util.ai.State;
import net.codepoke.lib.util.ai.game.Move;
import net.codepoke.lib.util.ai.search.PlayoutStrategy;
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

/**
 * Monte-Carlo Search that uses the Naive assumption to sample the local MABs of individual dimensions and feeds the
 * selected sub-actions into a global MAB.
 * 
 * See "The Combinatorial Multi-Armed Bandit Problem and its Application to Real-Time Strategy Games" by S. Ontanón
 * (2013).
 * 
 * @author GJ Roelofs <gj.roelofs@codepoke.net>
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 * 
 */
public class NMC {

	public static final Random rng = new MersenneTwister();

	/**
	 * The child-search uses its own policy to determine whether or not it should explore or exploit. Selection should
	 * therefore always be allowed, even if the current layer has not been fully visited.
	 * Note: this parameter should be kept at zero for this search to work correctly.
	 */
	public static final int ALWAYS_ALLOW_CHILD_SEARCH_NODE_SELECTION = 0;
	/**
	 * The child-search uses its own policy to determine whether or not it should explore or exploit. Expansion of a
	 * node should therefore always be allowed.
	 * Note: this parameter should be kept at zero for this search to work correctly.
	 */
	public static final int ALWAYS_ALLOW_CHILD_SEARCH_EXPANSION = 0;

	/**
	 * Assumptions made:
	 * 
	 * <pre>
	 * - The root call is always given a NaiveMonteCarloTreeNode
	 * - The expansion function in SearchContext expands into all sub-actions
	 * - All sub-actions need to be played, and are given in order.
	 * - A sequence of sub-actions is denoted by the current player in state switching.
	 * 
	 * cmab: Mapping that holds the root nodes of all individual MAB
	 * </pre>
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static NaiveMonteCarloSearchBuilder<Object, ? extends State, ? extends Move, Object, Object> constructParentNMCSearch(
			PlayoutStrategy playout, StateEvaluation evaluation, float epsilonParent, float epsilonChild, IntMap<TreeSearchNode> cmab,
			Function2<? extends Move, ? super State, ? super Array<TreeSearchNode>> merger) {

		NaiveMonteCarloSearchBuilder<Object, ? extends State, ? extends Move, Object, Object> search = NaiveMonteCarloSearch.builder();
		search.exploration((context, value) -> {
			return (rng.nextFloat() > epsilonParent);
		});
		search.solution(SolutionStrategy.Util.SOLUTION_ACTION);
		search.playout(playout);
		search.evaluation(evaluation);

		// Expansion: conduct a search for each individual sub-action, then combine and merge them into a
		// combined-action.
		search.expansion((parentContext, parentNode, oldState) -> {
			int dimension = 0;
			State state = parentContext.cloner()
										.clone(oldState);
			int currentPlayer = state.getPlayer();
			Array<TreeSearchNode> movesPerDimension = new Array<TreeSearchNode>();

			// Each expansion, clear previous information and disable report construction
			SearchContext context = parentContext.copy();
			context.clearResetters();
			context.constructReport(false);

			// Keep creating actions until we change player
			while (state.getPlayer() == currentPlayer && !context.goal()
																	.done(context, state)) {

				// Find the action for this local MAB
				context.search(constructChildNMCSearch(epsilonChild));
				context.source(state);
				context.startNode(cmab.get(dimension));
				context.execute();

				// Apply the action found and store the parent in the global CMAB for the next round
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

			// Check if the root node already contains the new node
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

	@SuppressWarnings("unchecked")
	public static NaiveMonteCarloSearch<Object, ? extends State, ? extends Move, Object, Object> constructChildNMCSearch(float epsilon) {
		// Set up the builder for the search
		NaiveMonteCarloSearchBuilder<Object, ? extends State, ? extends Move, Object, Object> builder = NaiveMonteCarloSearch.builder();

		// Create the selection and expansion strategies that are used in the final-selection strategy
		TreeSelection<State, Move> selection = TreeSelection.Util.selectBestNode(node -> {
			if (node.getVisits() == 0)
				return 0;
			else
				return node.getScore() / node.getVisits();
		}, ALWAYS_ALLOW_CHILD_SEARCH_NODE_SELECTION);
		TreeExpansion<State, Move> expansion = TreeExpansion.Util.<State, Move> createMinimumTExpansion(ALWAYS_ALLOW_CHILD_SEARCH_EXPANSION);

		// Store the node, for the next iteration, then return the best node
		builder.finalSelection((context, root) -> {

			if (root.getChildren().size == 0 || rng.nextFloat() > epsilon) {
				// Exploration: create a new action and add it to the pool (if no new action is found, revert to
				// Exploitation)
				TreeSearchNode<State, Move> node = expansion.expand(context, root, context.source());

				if (node.getPayload() != null)
					return node;
			}

			// Exploitation: Choose action from complete action pool
			TreeSearchNode<State, Move> nextSelectedNode = selection.selectNextNode(context, root);
			if (nextSelectedNode == null)
				nextSelectedNode = selection.selectNextNode(context, root);

			return nextSelectedNode;
		});

		builder.solution(SolutionStrategy.Util.SOLUTION_NODE);

		// The child-search returns to the parent after each iteration
		builder.iterations(0);

		// These next strategies are ignored for the child-search
		builder.playout((context, state) -> {
			return state;
		});
		builder.evaluation((context, node, state) -> {
			return 0;
		});
		builder.backPropagation((context, evaluation, node, state) -> {

		});

		return builder.build();
	}

}
