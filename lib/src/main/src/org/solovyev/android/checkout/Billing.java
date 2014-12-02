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

import android.app.Activity;
import android.app.Application;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import com.android.vending.billing.IInAppBillingService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import static java.lang.System.currentTimeMillis;
import static org.solovyev.android.checkout.ResponseCodes.ITEM_ALREADY_OWNED;
import static org.solovyev.android.checkout.ResponseCodes.ITEM_NOT_OWNED;

public final class Billing {

	private static final int API_VERSION = 3;

	
	private static final String TAG = "Checkout";

	public static final boolean DEBUG = true;

	
	private static final EmptyListener EMPTY_LISTENER = new EmptyListener();

	static final long SECOND = 1000L;
	static final long MINUTE = SECOND * 60L;
	static final long HOUR = MINUTE * 60L;
	static final long DAY = HOUR * 24L;

	
	private final Context context;


	private IInAppBillingService service;


	private volatile State state = State.INITIAL;


	private final Object lock = new Object();


	private CancellableExecutor mainThread;


	private final Configuration configuration;


	private final ConcurrentCache cache;


	private Executor background = Executors.newSingleThreadExecutor(new ThreadFactory() {
		@Override
		public Thread newThread( Runnable r) {
			return new Thread(r, "RequestThread");
		}
	});

	
	private final PendingRequests pendingRequests = new PendingRequests();

	
	private final BillingRequests requests = newRequestsBuilder().withTag(null).onBackgroundThread().create();

	
	private ServiceConnector connector = new DefaultServiceConnector();

	
	private PurchaseVerifier purchaseVerifier;

	/**
	 * Same as {@link #Billing(android.content.Context, android.os.Handler, Configuration)} with new handler
	 */
	public Billing( Context context,  Configuration configuration) {
		this(context, new Handler(), configuration);
		Check.isMainThread();
	}

	/**
	 * Creates an instance. After creation, it will be ready to use. This constructor does not
	 * block and is safe to call from a UI thread.
	 *
	 * @param context       application or activity context. Needed to bind to the in-app billing service.
	 * @param configuration billing configuration
	 */
	public Billing( Context context,  Handler handler,  Configuration configuration) {
		if (context instanceof Application) {
			// context.getApplicationContext() might return null for applications as we allow create Billing before
			// Application#onCreate is called
			this.context = context;
		} else {
			this.context = context.getApplicationContext();
		}
		this.mainThread = new MainThread(handler);
		this.configuration = new StaticConfiguration(configuration);
		Check.isNotEmpty(this.configuration.getPublicKey());
		this.cache = new ConcurrentCache(configuration.getCache());
		this.purchaseVerifier = configuration.getPurchaseVerifier();
	}

	
	public Context getContext() {
		return context;
	}

	
	Configuration getConfiguration() {
		return configuration;
	}

	
	ServiceConnector getConnector() {
		return connector;
	}

	/**
	 * Sometimes Google Play is not that fast in updating information on device. Let's wait it a little bit as if we
	 * don't wait we might cache expired information (though, it will be updated soon as RequestType#GET_PURCHASES
	 * cache entry expires quite often)
	 */
	static void waitGooglePlay() {
		try {
			Thread.sleep(100L);
		} catch (InterruptedException e) {
			error(e);
		}
	}

	void setService( IInAppBillingService service, boolean connecting) {
		synchronized (lock) {
			final State newState;
			if (connecting) {
				if (state != State.CONNECTING) {
					return;
				}
				if (service == null) {
					newState = State.FAILED;
				} else {
					newState = State.CONNECTED;
				}
			} else {
				if (state == State.INITIAL) {
					// preserve initial state
					return;
				}
				// service might be disconnected abruptly
				newState = State.DISCONNECTED;
			}
			this.service = service;
			setState(newState);
		}
	}

	void setConnector( ServiceConnector connector) {
		this.connector = connector;
	}

	void setBackground( Executor background) {
		this.background = background;
	}

	void setMainThread( CancellableExecutor mainThread) {
		this.mainThread = mainThread;
	}

	void setPurchaseVerifier( PurchaseVerifier purchaseVerifier) {
		this.purchaseVerifier = purchaseVerifier;
	}

	void setState( State newState) {
		synchronized (lock) {
			if (state != newState) {
				state = newState;
				switch (state) {
					case CONNECTED:
						executePendingRequests();
						break;
					case FAILED:
						mainThread.execute(new Runnable() {
							@Override
							public void run() {
								pendingRequests.onConnectionFailed();
							}
						});
						break;
				}
			}
		}
	}

	private void executePendingRequests() {
		background.execute(pendingRequests);
	}

	
	State getState() {
		synchronized (lock) {
			return state;
		}
	}

	public void connect() {
		synchronized (lock) {
			if (state == State.CONNECTED) {
				executePendingRequests();
				return;
			}
			if (state == State.CONNECTING) {
				return;
			}
			setState(State.CONNECTING);
			mainThread.execute(new Runnable() {
				@Override
				public void run() {
					connectOnMainThread();
				}
			});
		}
	}

	private void connectOnMainThread() {
		Check.isMainThread();
		final boolean connecting = connector.connect();
		if (!connecting) {
			setState(State.FAILED);
		}
	}

	void disconnect() {
		synchronized (lock) {
			if (state == State.DISCONNECTED || state == State.DISCONNECTING || state == State.INITIAL) {
				return;
			}
			setState(State.DISCONNECTING);
			mainThread.execute(new Runnable() {
				@Override
				public void run() {
					disconnectOnMainThread();
				}
			});
			pendingRequests.cancelAll();
		}
	}

	private void disconnectOnMainThread() {
		Check.isMainThread();
		connector.disconnect();
	}

	private int runWhenConnected( Request request,  Object tag) {
		return runWhenConnected(request, null, tag);
	}

	<R> int runWhenConnected( Request<R> request,  RequestListener<R> listener,  Object tag) {
		if (listener != null) {
			if (cache.hasCache()) {
				listener = new CachingRequestListener<R>(request, listener);
			}
			request.setListener(listener);
		}
		if (tag != null) {
			request.setTag(tag);
		}

		pendingRequests.add(onConnectedService(request));
		connect();

		return request.getId();
	}

	/**
	 * Cancels request with <var>requestId</var>
	 *
	 * @param requestId id of request
	 */
	public void cancel(int requestId) {
		pendingRequests.cancel(requestId);
	}

	/**
	 * Cancels all billing requests
	 */
	public void cancelAll() {
		pendingRequests.cancelAll();
	}

	
	private RequestRunnable onConnectedService( final Request request) {
		return new OnConnectedServiceRunnable(request);
	}

	/**
	 * @return new requests builder
	 */
	
	public RequestsBuilder newRequestsBuilder() {
		return new RequestsBuilder();
	}

	/**
	 * Requests executed on the returned object will be marked with <var>activity</var> tag and will be delivered on the
	 * main application thread
	 *
	 * @param activity activity
	 * @return requests for given <var>activity</var>
	 */
	
	public BillingRequests getRequests( Activity activity) {
		return new RequestsBuilder().withTag(activity).onMainThread().create();
	}

	/**
	 * Requests executed on the returned object will be marked with <var>service</var> tag and will be delivered on the
	 * main application thread
	 *
	 * @param service service
	 * @return requests for given <var>context</var>
	 */
	
	public BillingRequests getRequests( Service service) {
		return new RequestsBuilder().withTag(service).onMainThread().create();
	}

	/**
	 * Requests executed on the returned object will be marked with no tag and will be delivered on the
	 * main application thread
	 *
	 * @return default application requests
	 */
	
	public BillingRequests getRequests() {
		return requests;
	}

	
	Requests getRequests( Context context) {
		if (context instanceof Activity) {
			return (Requests) getRequests((Activity) context);
		} else if (context instanceof Service) {
			return (Requests) getRequests((Service) context);
		} else {
			Check.isNull(context);
			return (Requests) getRequests();
		}
	}

	@SuppressWarnings("unchecked")
	
	private static <R> RequestListener<R> emptyListener() {
		return EMPTY_LISTENER;
	}

	static void error( String message) {
		if (DEBUG) {
			Log.e(TAG, message);
		}
	}

	static void error( Exception e) {
		error(e.getMessage(), e);
	}

	static void error( String message,  Exception e) {
		if (e instanceof BillingException) {
			final BillingException be = (BillingException) e;
			switch (be.getResponse()) {
				case ResponseCodes.OK:
				case ResponseCodes.USER_CANCELED:
				case ResponseCodes.ACCOUNT_ERROR:
					if (DEBUG) {
						Log.e(TAG, message, e);
					}
					break;
				default:
					Log.e(TAG, message, e);
			}
		} else {
			Log.e(TAG, message, e);
		}
	}

	static void debug( String subTag,  String message) {
		if (DEBUG) {
			Log.d(TAG + "/" + subTag, message);
		}
	}

	static void debug( String message) {
		if (DEBUG) {
			Log.d(TAG, message);
		}
	}

	static void warning( String message) {
		if (DEBUG) {
			Log.w(TAG, message);
		}
	}

	/**
	 * @return default cache implementation
	 */
	
	public static Cache newCache() {
		return new MapCache();
	}

	/**
	 * @return default purchase verifier
	 */
	
	public static PurchaseVerifier newPurchaseVerifier( String publicKey) {
		return new DefaultPurchaseVerifier(publicKey);
	}

	
	PurchaseFlow createPurchaseFlow( Activity activity, int requestCode,  RequestListener<Purchase> listener) {
		if (cache.hasCache()) {
			listener = new RequestListenerWrapper<Purchase>(listener) {
				@Override
				public void onSuccess( Purchase result) {
					cache.removeAll(RequestType.GET_PURCHASES.getCacheKeyType());
					super.onSuccess(result);
				}
			};
		}
		return new PurchaseFlow(activity, requestCode, listener, purchaseVerifier);
	}

	/**
	 * Cancels listener recursively
	 *
	 * @param listener listener to be cancelled
	 */
	static void cancel( RequestListener<?> listener) {
		if (listener instanceof CancellableRequestListener) {
			((CancellableRequestListener) listener).cancel();
		}
	}

	
	<R> RequestListener<R> onMainThread( final RequestListener<R> listener) {
		return new MainThreadRequestListener<R>(mainThread, listener);
	}

	/**
	 * Service connection state
	 */
	enum State {
		/**
		 * Service is not connected, no requests can be done, initial state
		 */
		INITIAL,
		/**
		 * Service is connecting
		 */
		CONNECTING,
		/**
		 * Service is connected, requests can be executed
		 */
		CONNECTED,
		/**
		 * Service is disconnecting
		 */
		DISCONNECTING,
		/**
		 * Service is disconnected
		 */
		DISCONNECTED,
		/**
		 * Service failed to connect
		 */
		FAILED,
	}

	/**
	 * Dummy listener, used if user didn't provide {@link RequestListener}
	 *
	 * @param <R> type of result
	 */
	private static class EmptyListener<R> implements RequestListener<R> {
		@Override
		public void onSuccess( R result) {
		}

		@Override
		public void onError(int response,  Exception e) {
		}
	}

	private final class OnConnectedServiceRunnable implements RequestRunnable {


		private Request request;

		public OnConnectedServiceRunnable( Request request) {
			this.request = request;
		}

		@Override
		public boolean run() {
			final Request localRequest = getRequest();
			if (localRequest == null) {
				// request was cancelled => finish here
				return true;
			}

			if (checkCache(localRequest)) return true;

			// request is alive, let's check the service state
			final State localState;
			final IInAppBillingService localService;
			synchronized (lock) {
				localState = state;
				localService = service;
			}
			if (localState == State.CONNECTED) {
				Check.isNotNull(localService);
				// service is connected, let's start request
				try {
					localRequest.start(localService, API_VERSION, context.getPackageName());
				} catch (RemoteException e) {
					localRequest.onError(e);
				} catch (RequestException e) {
					localRequest.onError(e);
				} catch (RuntimeException e) {
					localRequest.onError(e);
				}
			} else {
				// service is not connected, let's check why
				if (localState != State.FAILED) {
					// service was disconnected
					connect();
					return false;
				} else {
					// service was not connected in the first place => can't do anything, aborting the request
					localRequest.onError(ResponseCodes.SERVICE_NOT_CONNECTED);
				}
			}

			return true;
		}

		private boolean checkCache( Request request) {
			if (cache.hasCache()) {
				final String key = request.getCacheKey();
				if (key != null) {
					final Cache.Entry entry = cache.get(request.getType().getCacheKey(key));
					if (entry != null) {
						//noinspection unchecked
						request.onSuccess(entry.data);
						return true;
					}
				}
			}
			return false;
		}

		@Override
		
		public Request getRequest() {
			synchronized (this) {
				return request;
			}
		}

		public void cancel() {
			synchronized (this) {
				if (request != null) {
					Billing.debug("Cancelling request: " + request);
					request.cancel();
				}
				request = null;
			}
		}

		@Override
		public int getId() {
			synchronized (this) {
				return request != null ? request.getId() : -1;
			}
		}

		
		@Override
		public Object getTag() {
			synchronized (this) {
				return request != null ? request.getTag() : null;
			}
		}

		@Override
		public String toString() {
			return String.valueOf(request);
		}
	}

	/**
	 * {@link org.solovyev.android.checkout.BillingRequests} builder. Allows to specify request tags and
	 * result delivery methods
	 */
	public final class RequestsBuilder {
		
		private Object tag;
		
		private Boolean onMainThread;

		private RequestsBuilder() {
		}

		
		public RequestsBuilder withTag( Object tag) {
			Check.isNull(this.tag);
			this.tag = tag;
			return this;
		}

		
		public RequestsBuilder onBackgroundThread() {
			Check.isNull(onMainThread);
			onMainThread = false;
			return this;
		}

		
		public RequestsBuilder onMainThread() {
			Check.isNull(onMainThread);
			onMainThread = true;
			return this;
		}

		
		public BillingRequests create() {
			return new Requests(tag, onMainThread == null ? true : onMainThread);
		}
	}

	final class Requests implements BillingRequests {

		
		private final Object tag;

		private final boolean onMainThread;

		private Requests( Object tag, boolean onMainThread) {
			this.tag = tag;
			this.onMainThread = onMainThread;
		}

		@Override
		public int isBillingSupported( String product) {
			return isBillingSupported(product, emptyListener());
		}

		@Override
		public int isBillingSupported( final String product,  RequestListener<Object> listener) {
			Check.isNotEmpty(product);
			return runWhenConnected(new BillingSupportedRequest(product), wrapListener(listener), tag);
		}

		
		private <R> RequestListener<R> wrapListener( RequestListener<R> listener) {
			return onMainThread ? onMainThread(listener) : listener;
		}

		
		Executor getDeliveryExecutor() {
			return onMainThread ? mainThread : SameThreadExecutor.INSTANCE;
		}

		@Override
		public int getPurchases( final String product,  final String continuationToken,  RequestListener<Purchases> listener) {
			Check.isNotEmpty(product);
			return runWhenConnected(new GetPurchasesRequest(product, continuationToken, purchaseVerifier), wrapListener(listener), tag);
		}

		@Override
		public int getAllPurchases( String product,  RequestListener<Purchases> listener) {
			Check.isNotEmpty(product);
			final GetAllPurchasesListener getAllPurchasesListener = new GetAllPurchasesListener(listener);
			final GetPurchasesRequest request = new GetPurchasesRequest(product, null, purchaseVerifier);
			getAllPurchasesListener.request = request;
			return runWhenConnected(request, wrapListener(getAllPurchasesListener), tag);
		}

		@Override
		public int isPurchased( final String product,  final String sku,  final RequestListener<Boolean> listener) {
			Check.isNotEmpty(sku);
			final IsPurchasedListener isPurchasedListener = new IsPurchasedListener(sku, listener);
			final GetPurchasesRequest request = new GetPurchasesRequest(product, null, purchaseVerifier);
			isPurchasedListener.request = request;
			return runWhenConnected(request, wrapListener(isPurchasedListener), tag);
		}

		@Override
		public int getSkus( String product,  List<String> skus,  RequestListener<Skus> listener) {
			Check.isNotEmpty(product);
			Check.isNotEmpty(skus);
			return runWhenConnected(new GetSkuDetailsRequest(product, skus), wrapListener(listener), tag);
		}

		@Override
		public int purchase( String product,  String sku,  String payload,  PurchaseFlow purchaseFlow) {
			Check.isNotEmpty(product);
			Check.isNotEmpty(sku);
			return runWhenConnected(new PurchaseRequest(product, sku, payload), wrapListener(purchaseFlow), tag);
		}

		@Override
		public int purchase( Sku sku,  String payload,  PurchaseFlow purchaseFlow) {
			return purchase(sku.product, sku.id, payload, purchaseFlow);
		}

		@Override
		public int consume( String token,  RequestListener<Object> listener) {
			Check.isNotEmpty(token);
			return runWhenConnected(new ConsumePurchaseRequest(token), wrapListener(listener), tag);
		}

		@Override
		public void cancelAll() {
			pendingRequests.cancelAll(tag);
		}

		/**
		 * This class waits for the result from {@link GetPurchasesRequest} and checks if purchases contains specified
		 * <var>sku</var>. If there is a <var>continuationToken</var> and item can't be found in this bulk of purchases
		 * another (recursive) request is executed (to load other purchases) and the search is done again. New (additional)
		 * request has the same ID and listener as original request, thus, can be cancelled with original request ID.
		 */
		private final class IsPurchasedListener implements CancellableRequestListener<Purchases> {

			
			private GetPurchasesRequest request;

			
			private final String sku;

			
			private final RequestListener<Boolean> listener;

			public IsPurchasedListener( String sku,  RequestListener<Boolean> listener) {
				this.sku = sku;
				this.listener = listener;
			}

			@Override
			public void onSuccess( Purchases purchases) {
				final Purchase purchase = purchases.getPurchase(sku);
				if (purchase != null) {
					listener.onSuccess(purchase.state == Purchase.State.PURCHASED);
				} else {
					// we need to check continuation token
					if (purchases.continuationToken != null) {
						request = new GetPurchasesRequest(request, purchases.continuationToken);
						runWhenConnected(request, tag);
					} else {
						listener.onSuccess(false);
					}
				}
			}

			@Override
			public void onError(int response,  Exception e) {
				listener.onError(response, e);
			}

			@Override
			public void cancel() {
				Billing.cancel(listener);
			}
		}

		private final class GetAllPurchasesListener implements CancellableRequestListener<Purchases> {

			
			private GetPurchasesRequest request;

			
			private final RequestListener<Purchases> listener;

			private final List<Purchase> result = new ArrayList<Purchase>();

			public GetAllPurchasesListener( RequestListener<Purchases> listener) {
				this.listener = listener;
			}

			@Override
			public void onSuccess( Purchases purchases) {
				result.addAll(purchases.list);
				// we need to check continuation token
				if (purchases.continuationToken != null) {
					request = new GetPurchasesRequest(request, purchases.continuationToken);
					runWhenConnected(request, tag);
				} else {
					listener.onSuccess(new Purchases(purchases.product, result, null));
				}
			}

			@Override
			public void onError(int response,  Exception e) {
				listener.onError(response, e);
			}

			@Override
			public void cancel() {
				Billing.cancel(listener);
			}
		}

	}

	private class CachingRequestListener<R> extends RequestListenerWrapper<R> {
		
		private final Request<R> request;

		public CachingRequestListener( Request<R> request,  RequestListener<R> listener) {
			super(listener);
			Check.isTrue(cache.hasCache(), "Cache must exist");
			this.request = request;
		}

		@Override
		public void onSuccess( R result) {
			final String key = request.getCacheKey();
			final RequestType type = request.getType();
			if (key != null) {
				final long now = currentTimeMillis();
				final Cache.Entry entry = new Cache.Entry(result, now + type.expiresIn);
				cache.putIfNotExist(type.getCacheKey(key), entry);
			}
			switch (type) {
				case PURCHASE:
				case CONSUME_PURCHASE:
					// these requests might affect the state of purchases => we need to invalidate caches.
					// see Billing#onPurchaseFinished() also
					cache.removeAll(RequestType.GET_PURCHASES.getCacheKeyType());
					break;
			}
			super.onSuccess(result);
		}

		@Override
		public void onError(int response,  Exception e) {
			final RequestType type = request.getType();
			// sometimes it is possible that cached data is not synchronized with data on Google Play => we can
			// clear caches if such situation occurred
			switch (type) {
				case PURCHASE:
					if (response == ITEM_ALREADY_OWNED) {
						cache.removeAll(RequestType.GET_PURCHASES.getCacheKeyType());
					}
					break;
				case CONSUME_PURCHASE:
					if (response == ITEM_NOT_OWNED) {
						cache.removeAll(RequestType.GET_PURCHASES.getCacheKeyType());
					}
					break;
			}
			super.onError(response, e);
		}
	}

	private final class DefaultServiceConnector implements ServiceConnector {

		
		private final ServiceConnection connection = new ServiceConnection() {
			@Override
			public void onServiceDisconnected(ComponentName name) {
				setService(null, false);
			}

			@Override
			public void onServiceConnected(ComponentName name,
										   IBinder service) {
				setService(IInAppBillingService.Stub.asInterface(service), true);
			}
		};

		@Override
		public boolean connect() {
			final Intent intent = new Intent("com.android.vending.billing.InAppBillingService.BIND");
			intent.setPackage("com.android.vending");
			return context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
		}

		@Override
		public void disconnect() {
			context.unbindService(connection);
		}
	}

	static interface ServiceConnector {
		boolean connect();

		void disconnect();
	}

	public abstract static class DefaultConfiguration implements Configuration {
		
		@Override
		public Cache getCache() {
			return newCache();
		}

		
		@Override
		public PurchaseVerifier getPurchaseVerifier() {
			Billing.warning("Default purchase verification procedure is used, please read https://github.com/serso/android-checkout#purchase-verification");
			return newPurchaseVerifier(getPublicKey());
		}

		
		@Override
		public Inventory getFallbackInventory( Checkout checkout,  Executor onLoadExecutor) {
			return null;
		}
	}

	public static interface Configuration {
		/**
		 * @return application's public key, encoded in base64.
		 * This is used for verification of purchase signatures. You can find app's base64-encoded
		 * public key in application's page on Google Play Developer Console. Note that this
		 * is NOT "developer public key".
		 */
		
		String getPublicKey();

		/**
		 * @return cache instance to be used for caching, null for no caching
		 * @see Billing#newCache()
		 */
		
		Cache getCache();

		/**
		 * @return {@link PurchaseVerifier} to be used to validate the purchases
		 * @see org.solovyev.android.checkout.PurchaseVerifier
		 */
		
		PurchaseVerifier getPurchaseVerifier();

		/**
		 * @return inventory to be used if Billing v.3 is not supported
		 * @param checkout checkout
		 * @param onLoadExecutor executor to be used to call {@link org.solovyev.android.checkout.Inventory.Listener} methods
		 */
		
		Inventory getFallbackInventory( Checkout checkout,  Executor onLoadExecutor);
	}

	/**
	 * Gets public key only once, all other methods are called from original configuration
	 */
	private static final class StaticConfiguration implements Configuration {
		
		private final Configuration original;

		
		private final String publicKey;

		private StaticConfiguration( Configuration original) {
			this.original = original;
			this.publicKey = original.getPublicKey();
		}

		
		@Override
		public String getPublicKey() {
			return publicKey;
		}

		
		@Override
		public Cache getCache() {
			return original.getCache();
		}

		
		@Override
		public PurchaseVerifier getPurchaseVerifier() {
			return original.getPurchaseVerifier();
		}

		
		@Override
		public Inventory getFallbackInventory( Checkout checkout,  Executor onLoadExecutor) {
			return original.getFallbackInventory(checkout, onLoadExecutor);
		}
	}

}
