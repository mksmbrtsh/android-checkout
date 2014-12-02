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

import java.util.ArrayList;
import java.util.List;


final class InventoryListeners implements Inventory.Listener {

	
	private final List<Inventory.Listener> list = new ArrayList<Inventory.Listener>();

	
	private final Object lock;

	InventoryListeners( Object lock) {
		this.lock = lock;
	}

	InventoryListeners() {
		this(new Object());
	}

	public void add( Inventory.Listener l) {
		synchronized (lock) {
			if (!list.contains(l)) {
				list.add(l);
			}
		}
	}

	@Override
	public void onLoaded( Inventory.Products products) {
		final List<Inventory.Listener> localList;
		synchronized (lock) {
			localList = new ArrayList<Inventory.Listener>(list);
			list.clear();
		}
		for (Inventory.Listener listener : localList) {
			listener.onLoaded(products);
		}
	}
}
