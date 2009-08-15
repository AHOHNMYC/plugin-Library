/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.Library.search;

import freenet.support.Logger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import plugins.Library.index.Request;
import plugins.Library.index.TermPageEntry;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import plugins.Library.index.TermEntry;
import plugins.Library.index.TermIndexEntry;
import plugins.Library.index.TermTermEntry;
import plugins.Library.serial.TaskAbortException;

/**
 * Unmodifiable Set which makes sure all data in results being combined is
 * retained and is created as the result of Set operations on other Sets. Does
 * not contain references to the original Sets.
 * <br /> <br />
 * The set isn't finalised until a run is finished and isDone() is true.
 *
 * @author MikeB
 */
public class ResultSet implements Set<TermEntry>, Runnable{
	private ResultOperation resultOperation;
	private boolean done = false;
	private Set<TermEntry>[] subresults;

	/**
	 * What should be done with the results of subsearches\n
	 * INTERSECTION : ( >2 subRequests) result is common results of subsearches\n
	 * UNION : ( >2 subRequests) result is all results of subsearches\n
	 * REMOVE : ( 2 subRequests) result is the results of the first subsearch with the second search removed
	 * PHRASE : ( >2 subRequests) For a phrase, the subsearches are in the order of the words in a phrase
	 * SINGLE : ( 1 subRequests) To encapsulate a single request
	 * DIFFERENTINDEXES : ( >2 subRequests) for a serch made up of requests on multiple indexes
	 */
	public enum ResultOperation{INTERSECTION, UNION, REMOVE, PHRASE, SINGLE, DIFFERENTINDEXES};

	private HashMap<TermEntry, TermEntry> internal;
	private final String subject;

	/**
	 * @param subject for each of the entries in the Set
	 * @param resultOperation to be performed on each of the given Sets {@see ResultOperation}
	 * @param subRequets List of subrequests to test each getResult()
	 * @throws plugins.Library.serial.TaskAbortException if thrown from a higher getResult() method
	 * @throws plugins.Library.search.IllegalArgumentException if the number of subRequests is not acceptable {@see ResultOperation}
	 */
	ResultSet(String subject, ResultOperation resultOperation, List<Request<Set<TermEntry>>> subRequests) throws TaskAbortException {
		if(resultOperation==ResultOperation.SINGLE && subRequests.size()!=1)
			throw new IllegalArgumentException(subRequests.size() + " requests supplied with SINGLE operation");
		if(resultOperation==ResultOperation.REMOVE && subRequests.size()!=2)
			throw new IllegalArgumentException("Negative operations can only have 2 parameters");
		if(		(	resultOperation==ResultOperation.PHRASE
					|| resultOperation == ResultOperation.INTERSECTION
					|| resultOperation == ResultOperation.UNION
					|| resultOperation == ResultOperation.DIFFERENTINDEXES )
				&& subRequests.size()<2)
			throw new IllegalArgumentException(resultOperation.toString() + " operations need more than one term");

		// Make sure any TaskAbortExceptions are found here and not when it's run
		subresults = getResultSets(subRequests);

		this.subject = subject;
		internal = new HashMap();
		this.resultOperation = resultOperation;
	}

	public synchronized void run() {
		if(done)
			throw new IllegalStateException("This ResultSet has already run and is finalised.");
		
		// Decide what to do
		switch(resultOperation){
			case SINGLE:	// Just add everything
				addAllToEmptyInternal(subresults[0]);
				break;
			case DIFFERENTINDEXES:	// Same as UNION currently
			case UNION:	// Add every one without overwriting
				unite(subresults);
				break;
			case INTERSECTION:	// Add one then retain the others
				intersect(subresults);
				break;
			case REMOVE:	// Add one then remove the other
				exclude(subresults[0], subresults[1]);
				break;
			case PHRASE:
				phrase(subresults);
				break;
		}
		done = true;
	}

	/**
	 * Copy a collection into a ResultSet
	 * @param subject to change the subject of each entry
	 * @param copy Collection to copy
	 */
	private ResultSet(String subject, Collection<? extends TermEntry> copy) {
		this.subject = subject;
		internal = new HashMap();
		addAllToEmptyInternal(copy);
	}


	// Set interface methods, folow the standard contract

	public int size() {
		return internal.size();
	}

	public boolean isEmpty() {
		return internal.isEmpty();
	}

	public boolean contains(Object o) {
		return internal.containsKey(o);
	}

	/**
	 * @return Iterator of Set, remove is unsupported
	 */
	public Iterator<TermEntry> iterator() {
		return new ResultIterator(internal.keySet().iterator());
	}

	public Object[] toArray() {
		return internal.keySet().toArray();
	}

	public <T> T[] toArray(T[] a) {
		return internal.keySet().toArray(a);
	}

	/**
	 * Not supported : Set is unmodifiable
	 * @throws UnsupportedOperationException always
	 */
	public boolean add(TermEntry e) {
		throw new UnsupportedOperationException("Set is unmodifiable.");
	}

	/**
	 * Not supported : Set is unmodifiable
	 * @throws UnsupportedOperationException always
	 */
	public boolean remove(Object o) {
		throw new UnsupportedOperationException("Set is unmodifiable.");
	}

	public boolean containsAll(Collection<?> c) {
		return internal.keySet().containsAll(c);
	}

	/**
	 * Not supported : Set is unmodifiable
	 * @throws UnsupportedOperationException always
	 */
	public boolean addAll(Collection<? extends TermEntry> c) {
		throw new UnsupportedOperationException("Set is unmodifiable.");
	}

	/**
	 * Not supported : Set is unmodifiable
	 * @throws UnsupportedOperationException always
	 */
	public boolean retainAll(Collection<?> c) {
		throw new UnsupportedOperationException("Set is unmodifiable.");
	}

	/**
	 * Not supported : Set is unmodifiable
	 * @throws UnsupportedOperationException always
	 */
	public boolean removeAll(Collection<?> c) {
		throw new UnsupportedOperationException("Set is unmodifiable.");
	}

	/**
	 * Not supported : Set is unmodifiable
	 * @throws UnsupportedOperationException always
	 */
	public void clear() {
		throw new UnsupportedOperationException("Set is unmodifiable.");
	}



	
	
	// Private internal functions for changing the set
	// TODO relevences, metadata extra, check copy constructors

	private void addInternal(TermEntry entry) {
		internal.put(entry, entry);
	}

	/**
	 * Overwrite add all entries in result to the internal Set converted to this
	 * ResultSet's subject. If the Set already has anything inside it
	 * you should use addAllInternal below. 
	 * @param result
	 */
	private void addAllToEmptyInternal(Collection<? extends TermEntry> result) {
		for (TermEntry termEntry : result) {
			TermEntry entry = convertEntry(termEntry);
			addInternal(entry);
		}
	}

	/**
	 * Add all entries in the first collection but not in the second
	 * @param result
	 */
	private void exclude(Collection<? extends TermEntry> add, Collection<? extends TermEntry> subtract) {
		for (TermEntry termEntry : add){
			if(getIgnoreSubject(termEntry, subtract)==null)
				addInternal(termEntry);
		}
	}

	/**
	 * Iterate over all the collections adding and merging all their entries
	 * @param collections to be merged into this collection
	 */
	private void unite(Collection<? extends TermEntry>... collections) {
		for(Collection<? extends TermEntry> c : collections)
			if(c==null)
				Logger.error(this, "the result was null");
			else
				for (TermEntry termEntry : c) {
					TermEntry entry = convertEntry(termEntry);
					if(contains(entry))
						addInternal(mergeEntries(internal.get(entry), entry));
					else
						addInternal(entry);
				}
	}

	/**
	 * Find the smallest collection, iterate over it and add elements which appear in all Collections
	 * @param collections
	 */
	private void intersect(Collection<? extends TermEntry>... collections) {
		// Find shortest collection
		int shortest = 0; int shortestsize = Integer.MAX_VALUE;
		for (int i = 1; i < collections.length; i++)
			if (collections[i].size() < shortestsize)
				shortest = i;

		Collection<? extends TermEntry> shortCollection = collections[shortest];
		// Iterate over it
		for (TermEntry termEntry : shortCollection) {
			// if term entry is in all the others, add it to this
			Set<TermEntry> entries = new HashSet(collections.length);
			entries.add(termEntry);

			for (int i = 0; i < collections.length; i++) {
				if(i==shortest)
					continue;	// dont compare against self

				Collection<? extends TermEntry> collection = collections[i];
				// See if collection contains termEntry
				TermEntry contains = getIgnoreSubject(termEntry, collection);
				if(contains != null)
					// add it to the entries
					entries.add(contains);
				else
					break;
			}
			// If all contained this entry, merge them all
			if(entries.size() == collections.length)
				addInternal(mergeEntries(entries.toArray(new TermEntry[0])));
		}
	}

	/**
	 * Iterate over the first collection and add elements which appear are followed in the next collection, repeat this operation with the others
	 * @param collections
	 */
	private void phrase(Collection<? extends TermEntry>... collections) {
		Collection<? extends TermEntry> firstCollection = collections[0];
		// Iterate over it
		for (TermEntry termEntry : firstCollection) {
			if(!(termEntry instanceof TermPageEntry))
				continue;
			// if term entry is followed in all the others, add it to this
			List<TermPageEntry> entries = new ArrayList<TermPageEntry>(collections.length);
			entries.add((TermPageEntry)termEntry);

			for (int i = 1; i < collections.length; i++) {
				Collection<? extends TermEntry> collection = collections[i];
				// See if collection follows termEntry
				TermPageEntry follow = follows(entries.get(i-1), collection);
				if(follow != null){
					// add it to the entries
					entries.add(follow);
				}else
					break;
			}
			// If all followed, merge them all
			if(entries.size() == collections.length)
				addInternal(mergeEntries(entries.toArray(new TermEntry[0])));
		}
		
	}

	private TermEntry convertEntry(TermEntry termEntry) {
		TermEntry entry;
		if (termEntry instanceof TermTermEntry)
			entry = new TermTermEntry( subject, ((TermTermEntry)termEntry).getTerm() );
		else if (termEntry instanceof TermPageEntry)
			entry = new TermPageEntry( subject, ((TermPageEntry)termEntry).getURI(), ((TermPageEntry)termEntry).getTitle(), ((TermPageEntry)termEntry).getPositions() );
		else if (termEntry instanceof TermIndexEntry)
			entry = new TermIndexEntry( subject, ((TermIndexEntry)termEntry).getIndex() );
		else
			throw new UnsupportedOperationException("The TermEntry type " + termEntry.getClass().getName() + " is not currently supported in ResultSet");
		if(termEntry.getRelevance()>0)
			entry.setRelevance(termEntry.getRelevance());
		return entry;
	}
	
	/**
	 * Merge a group of TermEntries each pair of which(a, b) must be a.equalsTarget
	 * The new TermEntry created will have the subject of this ResultSet, it
	 * will try to combine all the optional fields from the TermEntrys being merged,
	 * with priority being put on those earliest in the arguments
	 *
	 * @param entries
	 * @return
	 */
	private TermEntry mergeEntries(TermEntry... entries) {
		for (int i = 1; i < entries.length; i++)
			if(!entries[0].equalsTarget(entries[i]))
				throw new IllegalArgumentException("entries were not equal : "+entries[0].toString()+" & "+entries[i].toString());
		
		TermEntry combination = entries[0];

		// This mess could be replaced by a method in the TermEntrys which returns a new TermEntry with a changed subject
		if(combination instanceof TermIndexEntry){
			combination = new TermIndexEntry(subject, ((TermIndexEntry)combination).getIndex());
		} else if(combination instanceof TermPageEntry){
			combination = new TermPageEntry(subject, ((TermPageEntry)combination).getURI(), ((TermPageEntry)combination).getTitle(), ((TermPageEntry)combination).getPositions());
		} else if(combination instanceof TermTermEntry){
			combination = new TermTermEntry(subject, ((TermTermEntry)combination).getTerm());
		} else
			throw new IllegalArgumentException("Unknown type : "+combination.getClass().toString());

		if(entries[0].getRelevance()>0)
			combination.setRelevance(entries[0].getRelevance());
		
		for (int i = 1; i < entries.length; i++) {
			TermEntry termEntry = entries[i];
			combination = combine(combination, termEntry);
		}

		return combination;
	}

	private TermEntry combine(TermEntry entry1, TermEntry entry2) {
		if(!entry1.equalsTarget(entry2))
			throw new IllegalArgumentException("Combine can only be performed on equal TermEntrys");

		TermEntry newTermEntry;

		if(entry1 instanceof TermPageEntry){
			TermPageEntry pageentry1 = (TermPageEntry)entry1;
			TermPageEntry pageentry2 = (TermPageEntry)entry2;
			// Merge positions
			Map newPos = null;
			if(pageentry1.getPositions() == null && pageentry2.getPositions() != null)
				newPos = new HashMap(pageentry2.getPositions());
			else if(pageentry1.getPositions() != null){
				newPos = new HashMap(pageentry1.getPositions());
				if(pageentry2.getPositions() != null)
					newPos.putAll(pageentry2.getPositions());
			}

			newTermEntry = new TermPageEntry(pageentry1.getSubject(), pageentry1.getURI(), (pageentry1.getTitle()!=null)?pageentry1.getTitle():pageentry2.getTitle(), newPos);
		} else if(entry1 instanceof TermIndexEntry){
			TermIndexEntry castEntry = (TermIndexEntry) entry1;
			// nothing to merge, no optional fields	except relevance
			newTermEntry = new TermIndexEntry(castEntry.getSubject(), castEntry.getIndex());
		} else if(entry1 instanceof TermTermEntry){
			// nothing to merge, no optional fields	except relevance
			TermTermEntry castEntry = (TermTermEntry) entry1;

			newTermEntry = new TermTermEntry(castEntry.getSubject(), castEntry.getTerm());
		} else
			throw new UnsupportedOperationException("This type of TermEntry is not yet supported in the combine code : "+entry1.getClass().getName());


		// Merge rel
		float newRel = entry1.getRelevance() / ((entry2.getRelevance() == 0)? 1 : 2 ) + entry2.getRelevance() / ((entry1.getRelevance() == 0)? 1 : 2 );
		if(newRel>0)
			newTermEntry.setRelevance(newRel);

		return newTermEntry;
	}

	/**
	 * Strip all results out of List of requests
	 * @param subRequests
	 * @return
	 * @throws plugins.Library.serial.TaskAbortException from SubRequests {@link Request.getResult()}
	 */
	private Set<TermEntry>[] getResultSets(List<Request<Set<TermEntry>>> subRequests) throws TaskAbortException{
		Set<TermEntry>[] sets = new Set[subRequests.size()];
		for (int i = 0; i < subRequests.size(); i++) {
			sets[i] = subRequests.get(i).getResult();
		}
		return sets;
	}

	/**
	 * Gets a TermEntry from the collection which is equal to entry ignoring subject
	 * @param entry
	 * @param collection
	 * @return
	 */
	private TermEntry getIgnoreSubject(TermEntry entry, Collection<? extends TermEntry> collection){
		TermEntry result = null;
			for (TermEntry termEntry : collection) {
				if (entry.equalsTarget(termEntry)){
					result = termEntry;
					break;
				}
			}
		return result;
	}

	/**
	 * If entry is a TermPageEntry and there exists a TermPageEntry in collection
	 * which is equal ignoring the subject and contains at least one position
	 * which directly follows a position in entry, a new TermPageEntry is returned
	 * being a copy of the TermPageEntry found in collection with it's positions
	 * which do not directly follow positions in entry eliminated. Otherwise null
	 *
	 * @param entry
	 * @param collection
	 * @return
	 */
	private TermPageEntry follows(TermEntry entry, Collection<? extends TermEntry> collection){
		if(!(entry instanceof TermPageEntry))
			return null;
		TermPageEntry pageEntry1 = (TermPageEntry)entry;
		TermPageEntry pageEntry2 = (TermPageEntry)getIgnoreSubject(entry, collection);
		if(pageEntry1.getPositions() == null || pageEntry2 == null || pageEntry2.getPositions() == null)
			return null;

		Map<Integer, String> pos1 = pageEntry1.getPositions();
		Map<Integer, String> pos2 = pageEntry2.getPositions();
		if(pos1==null || pos2 == null)
			throw new NullPointerException("This index does not have term position information and so cannot perform a phrase search, this should probably be a different type of exception, maybe an InvalidSearchException? : "+pageEntry1.toString()+" "+pageEntry1.getPositions()+" / "+pageEntry2.toString()+" "+pageEntry2.getPositions());
		Map<Integer, String> newPos = new HashMap();
		for (Integer integer1 : pos1.keySet()) {
			for (Integer integer2 : pos2.keySet()) {
				if(integer1 == integer2+1)
					newPos.put(integer2, pos2.get(integer2));
			}
		}
		if(newPos.size()>0)
			return new TermPageEntry(pageEntry2.getSubject(), pageEntry2.getURI(), pageEntry2.getTitle(), newPos);
		else
			return null;
	}


	@Override public String toString(){
		return internal.keySet().toString();
	}

	public boolean isDone(){
		return done;
	}


	/**
	 * Iterator which doesnt allow remove()
	 */
	class ResultIterator implements Iterator<TermEntry> {
		Iterator<TermEntry> internal;

		private ResultIterator(Iterator<TermEntry> iterator) {
			internal = iterator;
		}

		public boolean hasNext() {
			return internal.hasNext();
		}

		public TermEntry next() {
			return internal.next();
		}

		public void remove() {
			throw new UnsupportedOperationException("Removal not allowed.");
		}

	}
}
