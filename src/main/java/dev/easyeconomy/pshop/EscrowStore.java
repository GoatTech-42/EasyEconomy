package dev.easyeconomy.pshop;

import dev.easyeconomy.pshop.tx.TxException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

// The escrow listing set and its durable flush, in plain java so the fault
// tests exercise this exact code. Every mutation rolls its memory change
// back when the flush fails, so memory and disk never disagree.
public final class EscrowStore {
	public interface Flush {
		void write(List<String> listingJsons) throws TxException;
	}

	private final Map<UUID, String> byId = new LinkedHashMap<>();
	private final Flush flush;

	public EscrowStore(Flush flush) {
		this.flush = flush;
	}

	public synchronized List<String> all() {
		return new ArrayList<>(byId.values());
	}

	public synchronized String get(UUID id) {
		return byId.get(id);
	}

	public synchronized boolean has(UUID id) {
		return byId.containsKey(id);
	}

	public synchronized int size() {
		return byId.size();
	}

	// Load path: memory only, no flush (disk is the source being read).
	public synchronized void loadAll(Collection<String> jsons, Function<String, UUID> idOf) {
		byId.clear();
		for (String json : jsons) {
			byId.put(idOf.apply(json), json);
		}
	}

	public synchronized void putPersist(UUID id, String json) throws TxException {
		byId.put(id, json);
		try {
			flush.write(new ArrayList<>(byId.values()));
		} catch (TxException e) {
			byId.remove(id);
			throw e;
		}
	}

	public synchronized void removePersist(UUID id) throws TxException {
		String removed = byId.remove(id);
		try {
			flush.write(new ArrayList<>(byId.values()));
		} catch (TxException e) {
			if (removed != null) {
				byId.put(id, removed);
			}
			throw e;
		}
	}
}
