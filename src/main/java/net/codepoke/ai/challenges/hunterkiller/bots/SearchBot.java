package net.codepoke.ai.challenges.hunterkiller.bots;

import lombok.Getter;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerAction;
import net.codepoke.ai.challenge.hunterkiller.HunterKillerState;
import net.codepoke.ai.challenge.hunterkiller.gameobjects.mapfeature.Structure;
import net.codepoke.lib.util.ai.SearchContext;
import net.codepoke.lib.util.ai.SearchContext.Status;

import com.googlecode.stateless4j.delegates.Func2;

/**
 * A bot that uses a search context, created for the game of HunterKiller, to search for the best action.
 * 
 * @author Anton Valkenberg (anton.valkenberg@gmail.com)
 *
 */
public class SearchBot<Position>
		extends BaseBot<HunterKillerState, HunterKillerAction> {

	/**
	 * Unique identifier, supplied by the AI-Competition for HunterKiller.
	 */
	private static final String myUID = "";
	/**
	 * Name of this bot as it is registered to the AI-Competition for HunterKiller.
	 */
	@Getter
	public final String botName = "SearchBot";

	/**
	 * Function that creates a Position-object from a {@link HunterKillerState}.
	 */
	private Func2<HunterKillerState, Position> positionSetup;
	/**
	 * The particular method of searching that this bot should use.
	 */
	private SearchContext<?, Position, ?, ?, HunterKillerAction> searchContext;

	/**
	 * Constructor.
	 * 
	 * @param searchContext
	 *            The particular method of searching that this bot should use.
	 * @param positionSetup
	 *            Function that creates a Position-object from a {@link HunterKillerState}.
	 */
	public SearchBot(SearchContext<?, Position, ?, ?, HunterKillerAction> searchContext, Func2<HunterKillerState, Position> positionSetup) {
		super(myUID, HunterKillerState.class, HunterKillerAction.class);
		this.searchContext = searchContext;
		this.positionSetup = positionSetup;
	}

	@Override
	public HunterKillerAction handle(HunterKillerState state) {
		// Check if we need to wait
		waitTimeBuffer();

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

		// Call the position setup method to create the Position to start the search from
		Position searchState = null;
		try {
			searchState = positionSetup.call(state);
		} catch (Exception e) {
			throw new RuntimeException(e.getMessage());
		}
		searchContext.source(searchState);

		// Tell the context to create a report
		searchContext.constructReport(true);

		// Search for an action
		searchContext.execute();

		// Print the Search's report
		System.out.println(searchContext.report());

		// Check if the search was successful
		if (searchContext.status() != Status.Success) {
			System.err.println("ERROR; search-context returned with status: " + searchContext.status());
			// Return a random action
			return RandomBot.createRandomAction(state);
		}

		// Get the solution of the search
		HunterKillerAction action = searchContext.solution();

		return action;
	}

}
