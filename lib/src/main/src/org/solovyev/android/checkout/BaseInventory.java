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



public abstract class BaseInventory implements Inventory {

	protected final Object lock;

	protected final Checkout checkout;


	protected Products products = Products.EMPTY;


	protected final InventoryListeners listeners;

	protected BaseInventory( Checkout checkout) {
		this.checkout = checkout;
		this.lock = checkout.lock;
		this.listeners = new InventoryListeners(this.lock);
	}

	@Override
	
	public final Products getProducts() {
		Check.isMainThread();
		synchronized (lock) {
			if (!isLoaded()) {
				Billing.warning("Inventory is not loaded yet. Use Inventory#whenLoaded");
			}
			return products;
		}
	}

	/**
	 * Note that this method should be called from inside of the synchronized block
	 * @return true if {@link #products} are loaded
	 */
	abstract boolean isLoaded();

	@Override
	public final void whenLoaded( Listener listener) {
		Check.isMainThread();
		synchronized (lock) {
			if (isLoaded()) {
				listener.onLoaded(products);
			} else {
				// still waiting
				listeners.add(listener);
			}
		}
	}
}
