/**
 * This file is part of Nori.
 * Copyright (c) 2013 Obscure Reference
 * License: GPLv3
 */
package pe.moe.nori;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.support.v4.util.LruCache;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.Toast;
import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.Window;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.ImageLoader;
import com.android.volley.toolbox.NetworkImageView;
import com.android.volley.toolbox.Volley;
import pe.moe.nori.adapters.ServiceDropdownAdapter;
import pe.moe.nori.api.BooruClient;
import pe.moe.nori.api.Image;
import pe.moe.nori.api.SearchResult;
import pe.moe.nori.providers.ServiceSettingsProvider;

import java.util.List;

public class SearchActivity extends SherlockFragmentActivity implements LoaderManager.LoaderCallbacks<List<ServiceSettingsProvider.ServiceSettings>> {
  /** Unique ID for the navigation dropdown {@link Loader} */
  private static final int SERVICE_DROPDOWN_LOADER = 0x00;
  /** ActionBar navigation dropdown {@link ActionBar.OnNavigationListener} */
  public ActionBar.OnNavigationListener mNavigationCallback = new ActionBar.OnNavigationListener() {

    @Override
    public boolean onNavigationItemSelected(int itemPosition, long itemId) {
      // Creates a new API client from the saved settings in the ServiceDropdownAdapter.
      mBooruClient = ServiceSettingsProvider.ServiceSettings.createClient(mRequestQueue,
          mServiceDropdownAdapter.getItem(itemPosition));

      // Searches for default query when:
      // * App is first created (mSearchResult == null).
      // * A new service was picked from the dropdown menu (itemId != mPref...).
      if (mBooruClient != null && (mSearchResult == null || itemId != mPreferences.getLong("last_service_dropdown_index", 0)))
        doSearch(mSharedPreferences.getString("default_query", mBooruClient.getDefaultQuery()));

      // Remember dropdown state to be restored when app is relaunched.
      mPreferences.edit().putLong("last_service_dropdown_index", itemId).apply();

      return true;
    }

  };
  /** Android Volley HTTP Request queue used for queuing API requests and image downloads. */
  public RequestQueue mRequestQueue;
  /** Last result size. Used when deciding to retain {@link SearchResult}s from previous instance */
  public long mLastResultSize = 0; // Used for saving instance state.
  /** {@link GridView} displaying search results */
  private GridView mGridView;
  /** LRU cache used for caching images */
  private LruCache<String, Bitmap> mLruCache = new LruCache<String, Bitmap>(2048) {
    @Override
    protected int sizeOf(String key, Bitmap value) {
      return (int) ((long) value.getRowBytes() * (long) value.getHeight() / 1048576);
    }
  };
  /** An {@link ImageLoader.ImageCache} implementation wrapping the {@link LruCache} for use with Android Volley. */
  private ImageLoader.ImageCache mImageCache = new ImageLoader.ImageCache() {
    @Override
    public Bitmap getBitmap(String url) {
      return mLruCache.get(url);
    }

    @Override
    public void putBitmap(String url, Bitmap bitmap) {
      mLruCache.put(url, bitmap);
    }
  };
  /** An image loader handling fetching, caching and putting images into views. */
  private ImageLoader mImageLoader;
  /** The {@link SearchResult} currently being displayed in this view. */
  private SearchResult mSearchResult = null;
  /** Android ActionBar */
  private ActionBar mActionBar;
  /** Persistent data for this activity */
  private SharedPreferences mPreferences;
  /** Persistent data for the entire app */
  private SharedPreferences mSharedPreferences;
  /** LoaderManager used to query settings database asynchronously */
  private LoaderManager mLoaderManager;
  /** Loads API settings from the database */
  private ServiceSettingsProvider mServiceSettingsProvider;
  /** Imageboard API client */
  private BooruClient mBooruClient;
  /** Adapter for the ActionBar navigation dropdown */
  private ServiceDropdownAdapter mServiceDropdownAdapter;
  /** Listener receiving parsed {@link SearchResult} responses from the API client */
  private Response.Listener<SearchResult> mSearchResultListener = new Response.Listener<SearchResult>() {
    @Override
    public void onResponse(SearchResult response) {
      // Hide progress bar.
      setProgressBarIndeterminateVisibility(false);

      if (mSearchResult == null) { // New result.
        mSearchResult = response;
      } else { // Load next page.
        mSearchResult.extend(response);
      }

      mSearchAdapter.notifyDataSetChanged();
    }
  };
  /** Listener receiving errors from the API client */
  private Response.ErrorListener mErrorListener = new Response.ErrorListener() {
    @Override
    public void onErrorResponse(VolleyError error) {
      // Hide progress bar.
      setProgressBarIndeterminateVisibility(false);
      // Log error.
      Log.e("SearchResultFetch", error.toString());
      // Show error notification to the user.
      Toast.makeText(SearchActivity.this, R.string.error_connection, Toast.LENGTH_SHORT).show();
    }
  };
  /** Adapter used by the {@link GridView */
  private BaseAdapter mSearchAdapter = new BaseAdapter() {
    @Override
    public int getCount() {
      return mSearchResult == null ? 0 : mSearchResult.images.size();
    }

    @Override
    public Image getItem(int position) {
      return mSearchResult.images.get(position);
    }

    @Override
    public long getItemId(int position) {
      // Return API image ID.
      return getItem(position).id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
      final NetworkImageView networkImageView;

      // Recycle view if possible.
      if (convertView == null) {
        // Create a new view.
        networkImageView = new NetworkImageView(SearchActivity.this);
        // Set properties.
        networkImageView.setLayoutParams(new GridView.LayoutParams(mGridView.getColumnWidth(), mGridView.getColumnWidth()));
        networkImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        networkImageView.setDefaultImageResId(R.color.background_image_loading);
      } else {
        // Recycle old view.
        networkImageView = (NetworkImageView) convertView;
      }

      // Set image URL.
      networkImageView.setImageUrl(getItem(position).previewUrl, mImageLoader);

      return networkImageView;
    }
  };

  private void doSearch(final String query) {
    // Give up if no API client available.
    if (mBooruClient == null)
      return;
    // Clear search result.
    mSearchResult = null;
    // Restarts and clears the request queue.
    mRequestQueue.start();
    // Show progress bar.
    setProgressBarIndeterminateVisibility(true);
    // Add request to queue.
    mRequestQueue.add(mBooruClient.searchRequest(query, mSearchResultListener, mErrorListener));
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // Request window manager features.
    requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
    // Get action bar.
    mActionBar = getSupportActionBar();
    // Get service settings provider.
    mServiceSettingsProvider = new ServiceSettingsProvider(this);
    // Get shared preferences.
    mPreferences = getPreferences(MODE_PRIVATE);
    mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
    // Prepare Volley request queue and ImageLoader.
    mRequestQueue = Volley.newRequestQueue(this);
    mImageLoader = new ImageLoader(mRequestQueue, mImageCache);
    // Inflate views.
    setContentView(R.layout.activity_search);
    // Get GridView and set adapter.
    mGridView = (GridView) findViewById(R.id.result_grid);
    mGridView.setAdapter(mSearchAdapter);
    // Get loader manager and setup navigation dropdown.
    mLoaderManager = getSupportLoaderManager();
    mLoaderManager.initLoader(SERVICE_DROPDOWN_LOADER, null, this).forceLoad();
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);

    // Save search result.
    outState.putParcelable("search_result", mSearchResult);
    // Save scroll position.
    outState.putInt("scroll_position", mGridView.getFirstVisiblePosition());
  }

  @Override
  public void onLowMemory() {
    super.onLowMemory();
    // Trim image cache.
    mLruCache.trimToSize(64);
  }

  public void onSearchResult(SearchResult searchResult) {
    mSearchResult.images.addAll(searchResult.images);
  }

  @Override
  protected void onRestoreInstanceState(Bundle savedInstanceState) {
    super.onRestoreInstanceState(savedInstanceState);

    // Restore search result.
    mSearchResult = savedInstanceState.getParcelable("search_result");
    mSearchAdapter.notifyDataSetChanged();
    // Restore scroll position.
    mGridView.setSelection(savedInstanceState.getInt("scroll_position"));
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    super.onCreateOptionsMenu(menu);

    // Inflate menu.
    final MenuInflater inflater = getSupportMenuInflater();
    inflater.inflate(R.menu.search, menu);
    // TODO: SearchView

    return true;
  }

  @Override
  public Loader<List<ServiceSettingsProvider.ServiceSettings>> onCreateLoader(final int i, final Bundle bundle) {
    if (i == SERVICE_DROPDOWN_LOADER) { // ActionBar navigation dropdown loader.
      return mServiceSettingsProvider.getServiceSettingsLoader();
    }
    return null;
  }

  @Override
  public void onLoadFinished(final Loader<List<ServiceSettingsProvider.ServiceSettings>> listLoader, List<ServiceSettingsProvider.ServiceSettings> serviceSettings) {
    if (listLoader.getId() == SERVICE_DROPDOWN_LOADER) {
      // Hide activity title and switch into list navigation mode.
      mActionBar.setDisplayShowTitleEnabled(false);
      mActionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);

      // Create the adapter for the navigation dropdown.
      mServiceDropdownAdapter = new ServiceDropdownAdapter(this, serviceSettings);
      mActionBar.setListNavigationCallbacks(mServiceDropdownAdapter, mNavigationCallback);

      // Select last item.
      final int lastSelectedIndex = mServiceDropdownAdapter.getPositionByItemId(mPreferences.getLong("last_service_dropdown_index", 0L));
      if (lastSelectedIndex != -1) {
        mActionBar.setSelectedNavigationItem(lastSelectedIndex);
      }
    }
  }

  @Override
  public void onLoaderReset(final Loader<List<ServiceSettingsProvider.ServiceSettings>> listLoader) {
    // Restore navigation mode and show activity title.
    mActionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
    mActionBar.setDisplayShowTitleEnabled(true);
  }
}
