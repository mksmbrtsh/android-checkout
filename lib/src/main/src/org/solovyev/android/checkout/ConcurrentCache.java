/*
 * Copyright 2014 serso aka se.solovyev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 * Contact details
 *
 * Email: se.solovyev@gmail.com
 * Site:  http://se.solovyev.org
 */

package org.solovyev.android.checkout;



import static java.lang.System.currentTimeMillis;

final class ConcurrentCache implements Cache {

	
	private static final String TAG = "Cache";

	
	
	private final Cache cache;

	ConcurrentCache( Cache cache) {
		this.cache = cache;
	}

	public boolean hasCache() {
		return cache != null;
	}

	@Override
	
	public Entry get( Key key) {
		if (cache != null) {
			synchronized (this) {
				final Entry entry = cache.get(key);
				if (entry == null) {
					Billing.debug(TAG, "Key=" + key + " is not in the cache");
					return null;
				}
				final long now = currentTimeMillis();
				if (now >= entry.expiresAt) {
					Billing.debug(TAG, "Key=" + key + " is in the cache but was expired at " + entry.expiresAt + ", now is " + now);
					cache.remove(key);
					return null;
				}
				Billing.debug(TAG, "Key=" + key + " is in the cache");
				return entry;
			}
		}

		return null;
	}

	@Override
	public void put( Key key,  Entry entry) {
		if (cache != null) {
			synchronized (this) {
				Billing.debug(TAG, "Adding entry with key=" + key + " to the cache");
				cache.put(key, entry);
			}
		}
	}

	public void putIfNotExist( Key key,  Entry entry) {
		if (cache != null) {
			synchronized (this) {
				if (cache.get(key) == null) {
					Billing.debug(TAG, "Adding entry with key=" + key + " to the cache");
					cache.put(key, entry);
				} else {
					Billing.debug(TAG, "Entry with key=" + key + " is already in the cache, won't add");
				}
			}
		}
	}

	@Override
	public void init() {
		if (cache != null) {
			synchronized (this) {
				Billing.debug(TAG, "Initializing cache");
				cache.init();
			}
		}
	}

	@Override
	public void remove( Key key) {
		if (cache != null) {
			synchronized (this) {
				Billing.debug(TAG, "Removing entry with key=" + key + " from the cache");
				cache.remove(key);
			}
		}
	}

	@Override
	public void removeAll(int type) {
		if (cache != null) {
			synchronized (this) {
				Billing.debug(TAG, "Removing all entries with type=" + type + " from the cache");
				cache.removeAll(type);
			}
		}
	}

	@Override
	public void clear() {
		if (cache != null) {
			synchronized (this) {
				Billing.debug(TAG, "Clearing the cache");
				cache.clear();
			}
		}
	}
}
