package net.codepoke.ai.challenges.hunterkiller;

import net.codepoke.ai.challenge.hunterkiller.Map;
import one.util.streamex.StreamEx;

public class StreamExtensions {

	/**
	 * Returns a StreamEx of objects of the specified target class on the specified {@link Map}.
	 * 
	 * @see <a href='https://github.com/amaembo/streamex/blob/master/CHEATSHEET.md'>StreamEx Cheatsheet</a>
	 * 
	 * @param map
	 *            The map to get the objects from.
	 * @param target
	 *            The target class to filter the objects on.
	 */
	public static <T> StreamEx<T> stream(Map map, Class<T> target) {
		return StreamEx.of(map.getObjects().items)
						.select(target);
	}

}
