/**
 * 
 */
package codemining.lm.grammar.tsg.feature;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.lang.math.RandomUtils;

import codemining.lm.grammar.tree.TreeNode;
import codemining.lm.grammar.tree.TreeNode.NodePair;
import codemining.lm.grammar.tsg.JavaFormattedTSGrammar;
import codemining.lm.grammar.tsg.TSGNode;
import codemining.util.SettingsLoader;

import com.google.common.base.Objects;
import com.google.common.base.Predicate;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multiset.Entry;

/**
 * Extract a featureset from a TSG and set of trees.
 * 
 * @author Miltos Allamanis <m.allamanis@ed.ac.uk>
 * 
 */
public class FeatureExtractor {

	/**
	 * A single TSG sample struct class.
	 * 
	 */
	public static class Sample {
		public final int tsgPatternId;

		public final List<Integer> previousNodes;

		public Sample(final int tsgPatternId, final List<Integer> previousNodes) {
			this.tsgPatternId = tsgPatternId;
			int start = previousNodes.size() - N_PREVIOUS_NODES;
			if (start < 0) {
				start = 0;
			}
			this.previousNodes = ImmutableList.copyOf(previousNodes.subList(
					start, previousNodes.size()));
		}

		@Override
		public boolean equals(final Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null) {
				return false;
			}
			if (getClass() != obj.getClass()) {
				return false;
			}
			final Sample other = (Sample) obj;
			if (tsgPatternId != other.tsgPatternId) {
				return false;
			}
			if (previousNodes == null) {
				if (other.previousNodes != null) {
					return false;
				}
			} else if (!previousNodes.equals(other.previousNodes)) {
				return false;
			}
			return true;
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(tsgPatternId, previousNodes);
		}
	}

	/**
	 * The number of previous nodes (in the pre-order trasversal) to include in
	 * a single sample.
	 */
	public static final int N_PREVIOUS_NODES = (int) SettingsLoader
			.getNumericSetting("nPreviousNodes", 10);

	/**
	 * The minimum size of a TSG rule to be considered as a pattern.
	 */
	public static final int MIN_SIZE_PATTERN = (int) SettingsLoader
			.getNumericSetting("minSizePattern", 6);

	/**
	 * The minimum count of a TSG rule to be considered a pattern.
	 */
	public static final int MIN_PATTERN_COUNT = (int) SettingsLoader
			.getNumericSetting("minPatternCount", 20);

	/**
	 * A predicate for comparing integer tree nodes.
	 */
	public static final Predicate<NodePair<Integer>> BASE_EQUALITY_COMPARATOR = new Predicate<NodePair<Integer>>() {

		@Override
		public boolean apply(final NodePair<Integer> nodePair) {
			return nodePair.fromNode.getData()
					.equals(nodePair.toNode.getData());
		}

	};

	static final Logger LOGGER = Logger.getLogger(FeatureExtractor.class
			.getName());

	/**
	 * The TSG patters for which we will create samples.
	 */
	private final BiMap<TreeNode<Integer>, Integer> tsgPatterns = HashBiMap
			.create();

	/**
	 * The next id of a TSG pattern in tsgPatterns. 0 is reserved for the
	 * unknown/no pattern.
	 */
	private int nextTsgPatternId = 1;

	/**
	 * The grammar form which we are extracting patterns.
	 */
	private final JavaFormattedTSGrammar tsGrammar;

	/**
	 * 
	 */
	public FeatureExtractor(final JavaFormattedTSGrammar grammar) {
		this.tsGrammar = grammar;
	}

	/**
	 * Add TSG grammar rules (aka patterns) to be suggested.
	 * 
	 * @param grammar
	 */
	public void addTreePatterns() {
		for (final Multiset<TreeNode<TSGNode>> rules : tsGrammar
				.getInternalGrammar().values()) {
			for (final Entry<TreeNode<TSGNode>> ruleEntry : rules.entrySet()) {
				if (ruleEntry.getCount() < MIN_PATTERN_COUNT
						|| TreeNode.getTreeSize(ruleEntry.getElement()) < MIN_SIZE_PATTERN) {
					continue;
				}
				tsgPatterns.put(TSGNode.tsgTreeToInt(ruleEntry.getElement()),
						nextTsgPatternId);
				nextTsgPatternId++;
			}
		}
	}

	/**
	 * Create samples for a single tree.
	 * 
	 * @param tree
	 * @return
	 */
	private Collection<Sample> createSamplesFor(final TreeNode<Integer> tree) {
		final Multiset<Sample> allSamples = HashMultiset.create();

		final ArrayList<Integer> previousNodes = Lists.newArrayList();

		final ArrayDeque<TreeNode<Integer>> toLook = new ArrayDeque<TreeNode<Integer>>();
		toLook.push(tree);

		// Do a pre-visit
		while (!toLook.isEmpty()) {
			final TreeNode<Integer> currentNode = toLook.pop();
			// at each node check if we have a partial match with any of the
			// patterns
			for (final TreeNode<Integer> pattern : tsgPatterns.keySet()) {
				if (pattern.partialMatch(currentNode, BASE_EQUALITY_COMPARATOR,
						true)) {
					// if we do, create sample and add
					allSamples.add(new Sample(tsgPatterns.get(pattern),
							previousNodes));
				} else if (RandomUtils.nextDouble() < .0001) {
					allSamples.add(new Sample(0, previousNodes));
				}
			}

			// Proceed visiting
			previousNodes.add(currentNode.getData());
			for (final List<TreeNode<Integer>> childProperties : currentNode
					.getChildrenByProperty()) {
				for (final TreeNode<Integer> child : childProperties) {
					toLook.push(child);
				}
			}

		}

		return allSamples;
	}

	/**
	 * Create for all templates a sample with the associated feature vector
	 * 
	 * @return
	 */
	public final Collection<Sample> createSamplesForPatterns(
			final Collection<File> files) {
		final List<TreeNode<Integer>> treeCorpus = Lists.newArrayList();
		for (final File f : files) {
			try {
				treeCorpus.add(tsGrammar.getJavaTreeExtractor().getTree(f));
			} catch (final IOException e) {
				LOGGER.warning(ExceptionUtils.getFullStackTrace(e));
			}
		}
		final Multiset<Sample> allSamples = HashMultiset.create();
		for (final TreeNode<Integer> tree : treeCorpus) {
			allSamples.addAll(createSamplesFor(tree));
		}
		return allSamples;
	}

	/**
	 * Convert a sample to a human-legible sample, using the grammar.
	 * 
	 * @param sample
	 * @return
	 */
	public String sampleToString(final Sample sample) {
		final StringBuilder builder = new StringBuilder();
		builder.append("Sample [tsgPattern=");
		if (sample.tsgPatternId != 0) {
			builder.append(tsGrammar.treeToString(TSGNode.convertTree(
					tsgPatterns.inverse().get(sample.tsgPatternId), 0)));
		} else {
			builder.append("NO_PATTERN");
		}
		builder.append(", previousNodes=[");
		for (final int prevNode : sample.previousNodes) {
			builder.append(tsGrammar.getJavaTreeExtractor().getSymbol(prevNode)
					+ ", ");
		}
		builder.append("]");
		return builder.toString();

	}

}