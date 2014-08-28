package com.cuddlesoft.nori.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.cuddlesoft.norilib.Image;

/**
 * Fragment used to display images not supported by the standard {@link android.widget.ImageView} widget
 * (such as animated GIFs).
 */
public class WebViewImageFragment extends ImageFragment {

  /**
   * Factory method used to construct new fragments.
   *
   * @param image Image object to display in the created fragment.
   * @return New WebViewImageFragment with the image object appended to its arguments bundle.
   */
  public static WebViewImageFragment newInstance(Image image) {
    // Create a new instance of the fragment.
    WebViewImageFragment fragment = new WebViewImageFragment();

    // Add the image object to its arguments Bundle.
    Bundle arguments = new Bundle();
    arguments.putParcelable(BUNDLE_ID_IMAGE, image);
    fragment.setArguments(arguments);

    return fragment;
  }

  /** Required empty public constructor. */
  public WebViewImageFragment() {
  }

  @Override
  public boolean canScroll(int direction) {
    return true;
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    // Initialize the WebView widget.
    WebView webView = new WebView(getActivity());

    // Set background color to black.
    webView.setBackgroundColor(0);
    webView.setBackgroundResource(android.R.color.black);

    // Zoom out the view port to display the entire image by default.
    WebSettings webSettings = webView.getSettings();
    webSettings.setUseWideViewPort(true);
    webSettings.setLoadWithOverviewMode(true);

    // Load image into the WebView.
    String imageUrl = shouldLoadImageSamples() ? image.sampleUrl : image.fileUrl;
    webView.loadUrl(imageUrl);

    return webView;
  }
}