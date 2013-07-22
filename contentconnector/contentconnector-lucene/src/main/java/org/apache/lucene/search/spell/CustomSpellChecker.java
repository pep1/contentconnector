package org.apache.lucene.search.spell;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.apache.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefIterator;

import com.gentics.cr.lucene.indexaccessor.IndexAccessor;
import com.gentics.cr.lucene.indexer.index.LuceneIndexLocation;
import com.gentics.cr.lucene.util.CRLuceneUtil;

/**
 * <p>
 * Spell Checker class (Main class) <br/>
 * (initially inspired by the David Spencer code).
 * </p>
 * 
 * <p>
 * Example Usage:
 * 
 * <pre>
 * SpellChecker spellchecker = new SpellChecker(spellIndexDirectory);
 * // To index a field of a user index:
 * spellchecker.indexDictionary(new LuceneDictionary(my_lucene_reader, a_field));
 * // To index a file containing words:
 * spellchecker.indexDictionary(new PlainTextDictionary(new File(&quot;myfile.txt&quot;)));
 * String[] suggestions = spellchecker.suggestSimilar(&quot;misspelt&quot;, 5);
 * </pre>
 * 
 * 
 * @version 1.0
 */
public class CustomSpellChecker implements java.io.Closeable {
	/**
	 * ten.
	 */
	private static final int TEN = 10;
	/**
	 * Logger.
	 */
	private static Logger log = Logger.getLogger(CustomSpellChecker.class);
	/**
	 * Field name for each word in the ngram index.
	 */
	public static final String F_WORD = "word";

	/**
	 * FWORDTERM.
	 */
	private static final Term F_WORD_TERM = new Term(F_WORD);

	/**
	 * the spell index.
	 */
	// don't modify the directory directly - see #swapSearcher()
	// TODO: why is this package private?
	private LuceneIndexLocation spellIndex;

	/**
	 * Boost value for start and end grams.
	 */
	private float bStart = 2.0f;
	/**
	 * Boost value for end.
	 */
	private float bEnd = 1.0f;

	/**
	 * don't use this searcher directly - see #swapSearcher().
	 */
	// private IndexSearcher searcher;

	/**
	 * this locks all modifications to the current searcher.
	 */
	private final Object searcherLock = new Object();

	/**
	 * this lock synchronizes all possible modifications to the current index
	 * directory. It should not be possible to try modifying the same index
	 * concurrently. Note: Do not acquire the searcher lock before acquiring
	 * this lock!
	 */
	private final Object modifyCurrentIndexLock = new Object();
	/**
	 * Closed.
	 */
	private volatile boolean closed = false;

	/**
	 * Default min score.
	 */
	private static final float MIN_SCORE = 0.5f;
	/**
	 * minimum score for hits generated by the spell checker query.
	 */
	private float minScore = MIN_SCORE;

	/**
	 * String distance.
	 */
	private StringDistance sd;

	/**
	 * min doc frequ.
	 */
	private int minDFreq = 1;

	/**
	 * Use the given directory as a spell checker index. The directory is
	 * created if it doesn't exist yet.
	 * 
	 * @param sspellIndex the spell index directory
	 * @param xsd the {@link org.apache.lucene.search.spell.StringDistance} measurement to use
	 * @throws IOException if Spellchecker can not open the directory
	 */
	public CustomSpellChecker(final LuceneIndexLocation sspellIndex, final StringDistance xsd) throws IOException {
		setSpellIndex(sspellIndex);
		setStringDistance(xsd);
	}

	/**
	 * Use the given directory as a spell checker index with a
	 * {@link org.apache.lucene.search.spell.LevensteinDistance} as the default 
	 * {@link org.apache.lucene.search.spell.StringDistance}. The
	 * directory is created if it doesn't exist yet.
	 * 
	 * @param xminScore min score
	 * @param xminDfreq min d freq
	 * @param sspellIndex the spell index directory
	 * @throws IOException if spellchecker can not open the directory
	 */
	public CustomSpellChecker(final LuceneIndexLocation sspellIndex, final Float xminScore, final Integer xminDfreq)
		throws IOException {
		this(sspellIndex, new LevensteinDistance());
		if (xminScore != null) {
			this.minScore = xminScore;
		}
		if (xminDfreq != null) {
			this.minDFreq = xminDfreq;
		}
	}

	/**
	 * Use a different index as the spell checker index or re-open the existing
	 * index if <code>spellIndex</code> is the same value as given in the
	 * constructor.
	 * 
	 * @param spellIndexDir the spell directory to use
	 * @throws IOException if spellchecker can not open the directory
	 */
	// TODO: we should make this final as it is called in the constructor
	public final void setSpellIndex(final LuceneIndexLocation spellIndexDir) throws IOException {
		// this could be the same directory as the current spellIndex
		// modifications to the directory should be synchronized
		synchronized (modifyCurrentIndexLock) {
			ensureOpen();
			this.spellIndex = spellIndexDir;
		}
	}

	/**
	 * Sets the {@link org.apache.lucene.search.spell.StringDistance} implementation for this 
	 * {@link org.apache.lucene.search.spell.SpellChecker} instance.
	 * 
	 * @param xsd the {@link org.apache.lucene.search.spell.StringDistance} implementation for 
	 * this {@link org.apache.lucene.search.spell.SpellChecker} instance
	 */
	public final void setStringDistance(final StringDistance xsd) {
		this.sd = xsd;
	}

	/**
	 * Returns the {@link org.apache.lucene.search.spell.StringDistance} instance used by this
	 * {@link org.apache.lucene.search.spell.SpellChecker} instance.
	 * 
	 * @return the {@link org.apache.lucene.search.spell.StringDistance} instance used by this
	 *         {@link org.apache.lucene.search.spell.SpellChecker} instance.
	 */
	public final StringDistance getStringDistance() {
		return sd;
	}

	/**
	 * Sets the accuracy 0 &lt; minScore &lt; 1; default 0.5.
	 * 
	 * @param xminScore
	 *            min score
	 */
	public final void setAccuracy(final float xminScore) {
		this.minScore = xminScore;
	}

	/**
	 * Suggest similar words.
	 * 
	 * <p>
	 * As the Lucene similarity that is used to fetch the most relevant
	 * n-grammed terms is not the same as the edit distance strategy used to
	 * calculate the best matching spell-checked word from the hits that Lucene
	 * found, one usually has to retrieve a couple of numSug's in order to get
	 * the true best match.
	 * 
	 * <p>
	 * I.e. if numSug == 1, don't count on that suggestion being the best one.
	 * Thus, you should set this value to <b>at least</b> 5 for a good
	 * suggestion.
	 * 
	 * @param word the word you want a spell check done on
	 * @param numSug the number of suggested words
	 * @throws IOException if the underlying index throws an {@link IOException}
	 * @return String[]
	 */
	public final String[] suggestSimilar(final String word, final int numSug) throws IOException {
		return this.suggestSimilar(word, numSug, null, null, false);
	}

	/**
	 * Suggest similar words (optionally restricted to a field of an index).
	 * 
	 * <p>
	 * As the Lucene similarity that is used to fetch the most relevant
	 * n-grammed terms is not the same as the edit distance strategy used to
	 * calculate the best matching spell-checked word from the hits that Lucene
	 * found, one usually has to retrieve a couple of numSug's in order to get
	 * the true best match.
	 * 
	 * <p>
	 * I.e. if numSug == 1, don't count on that suggestion being the best one.
	 * Thus, you should set this value to <b>at least</b> 5 for a good
	 * suggestion.
	 * 
	 * @param word the word you want a spell check done on
	 * @param numSug the number of suggested words
	 * @param ir the indexReader of the user index (can be null see field param)
	 * @param field
	 *            the field of the user index: if field is not null, the
	 *            suggested words are restricted to the words present in this
	 *            field.
	 * @param morePopular
	 *            return only the suggest words that are as frequent or more
	 *            frequent than the searched word (only if restricted mode =
	 *            (indexReader!=null and field!=null)
	 * @throws IOException
	 *             if the underlying index throws an {@link IOException}
	 * @return String[] the sorted list of the suggest words with these 2
	 *         criteria: first criteria: the edit distance, second criteria
	 *         (only if restricted mode): the popularity of the suggest words in
	 *         the field of the user index
	 */
	public final String[] suggestSimilar(final String word, final int numSug, final IndexReader ir, final String field,
			final boolean morePopular) throws IOException {
		// obtainSearcher calls ensureOpen
		ensureOpen();

		IndexAccessor ia = this.spellIndex.getAccessor();
		final IndexSearcher indexSearcher = (IndexSearcher) ia.getPrioritizedSearcher();

		try {
			float min = this.minScore;
			int minfrq = this.minDFreq;
			final int lengthWord = word.length();

			List<String> fieldnames = null;

			int intfreq = 0;
			if (ir != null) {
				if (field != null) {
					if ("all".equalsIgnoreCase(field) || (field != null && field.contains(","))) {
						if ("all".equalsIgnoreCase(field)) {
							fieldnames = CRLuceneUtil.getFieldNames(ir);
						} else {
							String[] arr = field.split(",");
							fieldnames = Arrays.asList(arr);
						}

						for (String fieldname : fieldnames) {
							intfreq += ir.docFreq(new Term(fieldname, word));
						}
					} else {
						intfreq += ir.docFreq(new Term(field, word));
					}
				}
			}

			final int freq = intfreq;
			int xfreq = 0;
			if (morePopular && ir != null && field != null) {
				xfreq = freq;
			}
			final int goalFreq = xfreq;

			// if the word exists in the real index and we don't
			// care for word frequency, return the word itself
			if (!morePopular && freq > 0) {
				return new String[] { word };
			}

			BooleanQuery query = new BooleanQuery();
			String[] grams;
			String key;

			for (int ng = getMin(lengthWord); ng <= getMax(lengthWord); ng++) {

				key = "gram" + ng; // form key

				grams = formGrams(word, ng); // form word into ngrams (allow
												// dups too)

				if (grams.length == 0) {
					continue; // hmm
				}

				if (bStart > 0) { // should we boost prefixes?
					add(query, "start" + ng, grams[0], bStart); // matches start
																// of word

				}
				if (bEnd > 0) { // should we boost suffixes
					add(query, "end" + ng, grams[grams.length - 1], bEnd);
					// matches end of word

				}
				for (int i = 0; i < grams.length; i++) {
					add(query, key, grams[i]);
				}
			}

			int maxHits = TEN * numSug;

			// System.out.println("Q: " + query);
			ScoreDoc[] hits = indexSearcher.search(query, null, maxHits).scoreDocs;
			// System.out.println("HITS: " + hits.length());
			CustomSuggestWordQueue sugQueue = new CustomSuggestWordQueue(numSug);

			// go thru more than 'maxr' matches in case the distance filter
			// triggers
			int stop = Math.min(hits.length, maxHits);
			CustomSuggestWord sugWord = new CustomSuggestWord();
			for (int i = 0; i < stop; i++) {

				sugWord.setString(indexSearcher.doc(hits[i].doc).get(F_WORD));
				// get orig word

				log.debug("DYM found term: " + sugWord.getString());

				// don't suggest a word for itself, that would be silly
				if (sugWord.getString().equals(word)) {
					log.debug("  Found word is the same as input word (" + word + ") -> next");
					continue;
				}

				// edit distance
				sugWord.setScore(sd.getDistance(word, sugWord.getString()));
				log.debug("  Distance score: " + sugWord.getScore());
				if (sugWord.getScore() < min) {
					log.debug("  Found word does not match min score (" + min + ") -> next");
					continue;
				}

				if (ir != null && field != null) { // use the user index

					String sugterm = sugWord.getString();
					int sugfreq = 0;
					if (fieldnames != null) {
						for (String fieldname : fieldnames) {
							sugfreq += ir.docFreq(new Term(fieldname, sugterm));
						}
					} else {
						sugfreq = ir.docFreq(new Term(field, sugterm));
					}
					sugWord.setFreq(sugfreq); // freq in the index
					log.debug("  DocFreq: " + sugWord.getFreq());
					// don't suggest a word that is not present in the field
					if ((morePopular && goalFreq > sugWord.getFreq()) || sugWord.getFreq() < minfrq) {
						log.debug("  Found word doese not match min frequency (" + minfrq + ") -> next");
						continue;
					}
				}
				sugQueue.insertWithOverflow(sugWord);
				if (sugQueue.size() == numSug) {
					// if queue full, maintain the minScore score
					min = sugQueue.top().getScore();
				}
				sugWord = new CustomSuggestWord();
			}

			// convert to array string
			String[] list = new String[sugQueue.size()];
			for (int i = sugQueue.size() - 1; i >= 0; i--) {
				list[i] = sugQueue.pop().getString();
			}

			return list;
		} finally {
			if (ia != null && indexSearcher != null) {
				ia.release(indexSearcher);
			}
		}
	}

	/**
	 * Add a clause to a boolean query.
	 * 
	 * @param q query
	 * @param name name
	 * @param value value
	 * @param boost boost
	 */
	private static void add(final BooleanQuery q, final String name, final String value, final float boost) {
		Query tq = new TermQuery(new Term(name, value));
		tq.setBoost(boost);
		q.add(new BooleanClause(tq, BooleanClause.Occur.SHOULD));
	}

	/**
	 * Add a clause to a boolean query.
	 * 
	 * @param q query
	 * @param name name
	 * @param value value
	 */
	private static void add(final BooleanQuery q, final String name, final String value) {
		q.add(new BooleanClause(new TermQuery(new Term(name, value)), BooleanClause.Occur.SHOULD));
	}

	/**
	 * Form all ngrams for a given word.
	 * 
	 * @param text the word to parse
	 * @param ng the ngram length e.g. 3
	 * @return an array of all ngrams in the word and note that duplicates are not removed
	 */
	private static String[] formGrams(final String text, final int ng) {
		int len = text.length();
		String[] res = new String[len - ng + 1];
		for (int i = 0; i < len - ng + 1; i++) {
			res[i] = text.substring(i, i + ng);
		}
		return res;
	}

	/**
	 * Removes all terms from the spell check index.
	 * 
	 * @throws IOException in case of error
	 */
	public final void clearIndex() throws IOException {
		ensureOpen();
		final IndexAccessor accessor = this.spellIndex.getAccessor();
		final IndexWriter writer = accessor.getWriter();
		try {
			writer.deleteAll();
			this.spellIndex.createReopenFile();
		} finally {
			if (accessor != null && writer != null) {
				accessor.release(writer);
			}
		}
	}

	/**
	 * Check whether the word exists in the index.
	 * 
	 * @param word word
	 * @throws IOException in case of error
	 * @return true if the word exists in the index
	 */
	public final boolean exist(final String word) throws IOException {
		ensureOpen();
		final IndexAccessor accessor = this.spellIndex.getAccessor();
		final IndexSearcher indexSearcher = (IndexSearcher) accessor.getPrioritizedSearcher();
		try {
			return indexSearcher.docFreq(F_WORD_TERM.createTerm(word)) > 0;
		} finally {
			if (accessor != null && indexSearcher != null) {
				accessor.release(indexSearcher);
			}
		}
	}

	/**
	 * Three.
	 */
	private static final int THREE = 3;

	/**
	 * Indexes the data from the given {@link Dictionary}.
	 * 
	 * @param dict Dictionary to index
	 * @throws IOException in case of error
	 */
	public final void indexDictionary(final Dictionary dict) throws IOException {
		synchronized (modifyCurrentIndexLock) {
			ensureOpen();

			final IndexAccessor accessor = this.spellIndex.getAccessor();
			final IndexWriter writer = accessor.getWriter();
			writer.setMergeFactor(300);
			final IndexSearcher indexSearcher = (IndexSearcher) accessor.getPrioritizedSearcher();
			int obj_count = 0;

			try {
				BytesRefIterator iter = dict.getWordsIterator();
				BytesRef ref = iter.next();
				while (ref != null) {
					String word = ref.utf8ToString();

					int len = word.length();
					if (len < THREE) {
						ref = iter.next();
						continue; // too short we bail but "too long" is fine...
					}

					if (indexSearcher.docFreq(F_WORD_TERM.createTerm(word)) > 0) {
						// if the word already exist in the gramindex
						ref = iter.next();
						continue;
					}

					// ok index the word
					Document doc = createDocument(word, getMin(len), getMax(len));
					writer.addDocument(doc);
					obj_count++;
					ref = iter.next();
				}

			} finally {
				// if documents where added to the index create a reopen file and
				// optimize the writer
				if (obj_count > 0) {
					writer.optimize();
					this.spellIndex.createReopenFile();
				}
				accessor.release(writer);
				accessor.release(indexSearcher);
			}
		}
	}

	/**
	 * 1.
	 */
	private static final int ONE = 1;
	/**
	 * 2.
	 */
	private static final int TWO = 2;
	/**
	 * 4.
	 */
	private static final int FOUR = 4;
	/**
	 * 5.
	 */
	private static final int FIVE = 5;

	/**
	 * Indexes the data from the given {@link Dictionary}.
	 * 
	 * @param dict the dictionary to index
	 * @throws IOException in case of error
	 */
	// public final void indexDictionary(final Dictionary dict) throws
	// IOException {
	// indexDictionary(dict, THREE_HUNDRED, TEN);
	// }

	/**
	 * get Min.
	 * 
	 * @param l
	 *            l
	 * @return min
	 */
	private static int getMin(final int l) {
		if (l > FIVE) {
			return THREE;
		}
		if (l == FIVE) {
			return TWO;
		}
		return ONE;
	}

	/**
	 * get max.
	 * 
	 * @param l
	 * @return max
	 */
	private static int getMax(final int l) {
		if (l > FIVE) {
			return FOUR;
		}
		if (l == FIVE) {
			return THREE;
		}
		return TWO;
	}

	/**
	 * create document.
	 * 
	 * @param text t
	 * @param ng1 ng
	 * @param ng2 ng
	 * @return document
	 */
	private static Document createDocument(final String text, final int ng1, final int ng2) {
		Document doc = new Document();
		doc.add(new Field(F_WORD, text, Field.Store.YES, Field.Index.NOT_ANALYZED));
		// orig term
		addGram(text, doc, ng1, ng2);
		return doc;
	}

	/**
	 * add Gram.
	 * 
	 * @param text t
	 * @param doc d
	 * @param ng1 n
	 * @param ng2 n
	 */
	private static void addGram(final String text, final Document doc, final int ng1, final int ng2) {
		int len = text.length();
		for (int ng = ng1; ng <= ng2; ng++) {
			String key = "gram" + ng;
			String end = null;
			for (int i = 0; i < len - ng + 1; i++) {
				String gram = text.substring(i, i + ng);
				doc.add(new Field(key, gram, Field.Store.NO, Field.Index.NOT_ANALYZED));
				if (i == 0) {
					doc.add(new Field("start" + ng, gram, Field.Store.NO, Field.Index.NOT_ANALYZED));
				}
				end = gram;
			}
			if (end != null) { // may not be present if len==ng1
				doc.add(new Field("end" + ng, end, Field.Store.NO, Field.Index.NOT_ANALYZED));
			}
		}
	}

	// /**
	// * obtainSearcher.
	// * @return searcher
	// * @throws IOException
	// */
	// private IndexSearcher obtainSearcher() throws IOException {
	// synchronized (searcherLock) {
	// ensureOpen();
	// IndexSearcher mySearcher = (IndexSearcher)
	// this.spellIndex.getAccessor().getSearcher();
	// return mySearcher;
	// }
	// }

	/**
	 * ensure open.
	 */
	private void ensureOpen() {
		if (closed) {
			throw new AlreadyClosedException("Spellchecker has been closed");
		}
	}

	/**
	 * Stops the IndexLocation used by this SpellChecker.
	 * 
	 */
	public final void close() {
		ensureOpen();
		closed = true;
		this.spellIndex.stop();
	}

	/**
	 * Creates a new read-only IndexSearcher.
	 * 
	 * @param dir
	 *            the directory used to open the searcher
	 * @return a new read-only IndexSearcher
	 * @throws IOException
	 *             f there is a low-level IO error
	 */
	// for testing purposes
	final IndexSearcher createSearcher(final Directory dir) throws IOException {
		return new IndexSearcher(dir, true);
	}

	/**
	 * Returns <code>true</code> if and only if the {@link SpellChecker} is
	 * closed, otherwise <code>false</code>.
	 * 
	 * @return <code>true</code> if and only if the {@link SpellChecker} is
	 *         closed, otherwise <code>false</code>.
	 */
	final boolean isClosed() {
		return closed;
	}

}
