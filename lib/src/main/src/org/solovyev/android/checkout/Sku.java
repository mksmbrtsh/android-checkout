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

import android.text.TextUtils;
import org.json.JSONException;
import org.json.JSONObject;


public final class Sku {

	
	public final String product;

	
	public final String id;

	
	public final String price;

	
	public final Price detailedPrice;

	
	public final String title;

	
	public final String description;

	Sku( String product,  String id,  String price,  Price detailedPrice,  String title,  String description) {
		this.product = product;
		this.id = id;
		this.price = price;
		this.detailedPrice = detailedPrice;
		this.title = title;
		this.description = description;
	}

	
	static Sku fromJson( String json,  String product) throws JSONException {
		final JSONObject object = new JSONObject(json);
		final String sku = object.getString("productId");
		final String price = object.getString("price");
		final Price detailedPrice = Price.fromJson(object);
		final String title = object.getString("title");
		final String description = object.optString("description");
		return new Sku(product, sku, price, detailedPrice, title, description);
	}

	/**
	 * Contains detailed information about SKU's price as described <a href="http://developer.android.com/google/play/billing/billing_reference.html#getSkuDetails">here</a>
	 */
	public static final class Price {

		
		static final Price EMPTY = new Price(0, "");

		public final long amount;

		
		public final String currency;

		private Price(long amount,  String currency) {
			this.amount = amount;
			this.currency = currency;
		}

		
		private static Price fromJson( JSONObject json) throws JSONException {
			final long amount = json.optLong("price_amount_micros");
			final String currency = json.optString("price_currency_code");
			if (amount == 0 || TextUtils.isEmpty(currency)) {
				return EMPTY;
			} else {
				return new Price(amount, currency);
			}
		}

		/**
		 * @return true if both {@link #amount} and {@link #currency} are valid (non empty)
		 */
		public boolean isValid() {
			return amount > 0 && !TextUtils.isEmpty(currency);
		}
	}
}
