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

import android.os.RemoteException;
import com.android.vending.billing.IInAppBillingService;


import static org.solovyev.android.checkout.RequestType.BILLING_SUPPORTED;

final class BillingSupportedRequest extends Request<Object> {

	
	private final String product;

	BillingSupportedRequest( String product) {
		super(BILLING_SUPPORTED);
		this.product = product;
	}

	@Override
	public void start( IInAppBillingService service, int apiVersion,  String packageName) throws RemoteException {
		final int response = service.isBillingSupported(apiVersion, packageName, product);
		if (!handleError(response)) {
			onSuccess(new Object());
		}
	}

	
	@Override
	protected String getCacheKey() {
		return product;
	}
}
