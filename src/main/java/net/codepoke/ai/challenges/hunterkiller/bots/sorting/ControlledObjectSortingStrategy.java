package net.codepoke.ai.challenges.hunterkiller.bots.sorting;

import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;

import com.badlogic.gdx.utils.IntArray;

/**
 * Defines what a method that sorts controlled objects should support.
 * 
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
public interface ControlledObjectSortingStrategy {

	/**
	 * Sorts the IDs of the objects controlled by the active player into an ordered {@link IntArray}.
	 * 
	 * @param state
	 *            The game state that contains the objects that should be sorted.
	 */
	public IntArray sort(HunterKillerState state);

	/**
	 * Allows some post processing to be run on the current sorting when a certain index in the ordering is to be
	 * expanded next.
	 * 
	 * @param currentOrdering
	 *            The current ordering of dimensions.
	 * @param nextDimensionIndex
	 *            The index of the dimension that should be expanded next.
	 */
	public void postProcess(IntArray currentOrdering, int nextDimensionIndex);

}
