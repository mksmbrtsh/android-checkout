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

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static android.database.sqlite.SQLiteDatabase.OPEN_READONLY;
import static android.database.sqlite.SQLiteDatabase.openDatabase;

public final class RobotmediaDatabase {

	static final String NAME = "billing.db";

	
	private final Context context;

	public RobotmediaDatabase( Context context) {
		this.context = context;
	}

	public static boolean exists( Context context) {
		final File file = getDatabaseFile(context);
		return file != null && file.exists();
	}

	
	private static File getDatabaseFile( Context context) {
		return context.getDatabasePath(NAME);
	}

	
	static String getDatabasePath( Context context) {
		final File file = getDatabaseFile(context);
		return file != null ? file.getPath() : null;
	}

	
	Inventory.Products load( Products products) {
		SQLiteDatabase db = null;
		try {
			final String databasePath = RobotmediaDatabase.getDatabasePath(context);
			db = openDatabase(databasePath, null, OPEN_READONLY);
			return loadProducts(products, db);
		} catch (RuntimeException e) {
			Billing.error(e);
		} finally {
			if (db != null) {
				db.close();
			}
		}
		return toInventoryProducts(products);
	}

	
	static Inventory.Products toInventoryProducts( Products products) {
		final Inventory.Products result = new Inventory.Products();
		for (String productId : products.getIds()) {
			result.add(new Inventory.Product(productId, true));
		}
		return result;
	}

	
	private Inventory.Products loadProducts( Products products,  SQLiteDatabase db) {
		final Inventory.Products result = new Inventory.Products();
		for (String productId : products.getIds()) {
			final Inventory.Product product = new Inventory.Product(productId, true);

			final List<String> skus = products.getSkuIds(productId);
			if (!skus.isEmpty()) {
				product.setPurchases(loadPurchases(skus, db));
			}

			result.add(product);
		}
		return result;
	}

	
	private List<Purchase> loadPurchases( List<String> skus,  SQLiteDatabase db) {
		Check.isNotEmpty(skus);
		final List<Purchase> purchases = new ArrayList<Purchase>(skus.size());
		final String[] columns = {"_id", "state", "productId", "purchaseTime", "developerPayload"};
		final String packageName = context.getPackageName();

		Cursor c = null;
		try {
			c = db.query("purchases", columns, "productId in " + makeInClause(skus.size()), skus.toArray(new String[skus.size()]), null, null, null);
			if (c.moveToFirst()) {
				do {
					final String orderId = c.getString(0);
					final int state = c.getInt(1);
					final String sku = c.getString(2);
					final long time = c.getLong(3);
					final String payload = c.getString(4);
					final Purchase p = new Purchase(sku, orderId, packageName, time, state, payload, "", "", "");
					purchases.add(p);
				} while (c.moveToNext());
			}
		} finally {
			if (c != null) {
				c.close();
			}
		}
		return purchases;
	}

	
	static String makeInClause(int count) {
		Check.isTrue(count > 0, "Should be positive");
		final StringBuilder sb = new StringBuilder(count * 2 + 1);
		sb.append("(");
		sb.append("?");
		for (int i = 1; i < count; i++) {
			sb.append(",?");
		}
		sb.append(")");
		return sb.toString();
	}
}
