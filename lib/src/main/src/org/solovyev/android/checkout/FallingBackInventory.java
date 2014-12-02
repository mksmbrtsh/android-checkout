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


/**
 * Inventory which falls back to fallback {@link Inventory} if one of the products is not supported.
 */
class FallingBackInventory extends BaseInventory {

	
	private final CheckoutInventory mainInventory;

	
	private final Inventory fallbackInventory;

	
	private final MainListener mainListener = new MainListener();

	
	private final FallbackListener fallbackListener = new FallbackListener();

	public FallingBackInventory( Checkout checkout,  Inventory fallbackInventory) {
		super(checkout);
		this.mainInventory = new CheckoutInventory(checkout);
		this.fallbackInventory = fallbackInventory;
	}

	
	@Override
	public Inventory load() {
		mainListener.onLoad();
		mainInventory.load().whenLoaded(mainListener);
		return this;
	}

	boolean isLoaded() {
		synchronized (lock) {
			return products == mainListener.myProducts;
		}
	}

	private class MainListener implements Listener {

		
		private volatile Products myProducts;

		@Override
		public void onLoaded( Products products) {
			if (myProducts != null) {
				return;
			}
			if (existsUnsupported(products)) {
				fallbackListener.products = products;
				fallbackInventory.load().whenLoaded(fallbackListener);
			} else {
				onProductsLoaded(products);
			}
		}

		private boolean existsUnsupported( Products products) {
			for (Product product : products) {
				if (!product.supported) {
					return true;
				}
			}

			return false;
		}

		public void onLoad() {
			myProducts = null;
		}
	}

	private class FallbackListener implements Listener {

		
		private volatile Products products;

		@Override
		public void onLoaded( Products products) {
			this.products.merge(products);
			onProductsLoaded(this.products);
		}
	}

	private void onProductsLoaded( Products products) {
		synchronized (lock) {
			this.mainListener.myProducts = products;
			this.products = products;
			listeners.onLoaded(products);
		}
	}
}
