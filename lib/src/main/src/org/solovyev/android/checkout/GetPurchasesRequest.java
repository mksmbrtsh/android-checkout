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

import android.os.Bundle;
import android.os.RemoteException;
import com.android.vending.billing.IInAppBillingService;
import org.json.JSONException;

import java.util.List;

import static org.solovyev.android.checkout.ResponseCodes.EXCEPTION;

final class GetPurchasesRequest extends Request<Purchases> {

	
	private final String product;

	
	private final String continuationToken;

	
	private final PurchaseVerifier verifier;

	GetPurchasesRequest( String product,  String continuationToken,  PurchaseVerifier verifier) {
		super(RequestType.GET_PURCHASES);
		this.product = product;
		this.continuationToken = continuationToken;
		this.verifier = verifier;
	}

	GetPurchasesRequest( GetPurchasesRequest request,  String continuationToken) {
		super(RequestType.GET_PURCHASES, request);
		this.product = request.product;
		this.continuationToken = continuationToken;
		this.verifier = request.verifier;
	}

	
	String getProduct() {
		return product;
	}

	
	String getContinuationToken() {
		return continuationToken;
	}

	@Override
	void start( IInAppBillingService service, int apiVersion,  String packageName) throws RemoteException {
		final Bundle bundle = service.getPurchases(apiVersion, packageName, product, continuationToken);
		if (!handleError(bundle)) {
			try {
				final String continuationToken = Purchases.getContinuationTokenFromBundle(bundle);
				final List<Purchase> purchases = Purchases.getListFromBundle(bundle);
				if (!purchases.isEmpty()) {
					final VerificationListener listener = new VerificationListener(this, product, continuationToken);
					verifier.verify(purchases, listener);
					if (!listener.called) {
						listener.onError(ResponseCodes.EXCEPTION, new IllegalStateException("Either onSuccess or onError methods must be called by PurchaseVerifier"));
					}
				} else {
					onSuccess(new Purchases(product, purchases, continuationToken));
				}
			} catch (JSONException e) {
				onError(e);
			}
		}
	}

	
	@Override
	protected String getCacheKey() {
		if (continuationToken != null) {
			return product + "_" + continuationToken;
		} else {
			return product;
		}
	}

	private static class VerificationListener implements RequestListener<List<Purchase>> {
		
		private final Request<Purchases> request;
		
		private final String product;
		
		private final String continuationToken;
		
		private final Thread thread;
		private boolean called;

		public VerificationListener( Request<Purchases> request,  String product,  String continuationToken) {
			this.request = request;
			this.product = product;
			this.continuationToken = continuationToken;
			this.thread = Thread.currentThread();
		}

		@Override
		public void onSuccess( List<Purchase> verifiedPurchases) {
			Check.equals(thread, Thread.currentThread(), "Must be called on the same thread");
			called = true;
			request.onSuccess(new Purchases(product, verifiedPurchases, continuationToken));
		}

		@Override
		public void onError(int response,  Exception e) {
			Check.equals(thread, Thread.currentThread(), "Must be called on the same thread");
			called = true;
			if (response == EXCEPTION) {
				request.onError(e);
			} else {
				request.onError(response);
			}
		}
	}
}
