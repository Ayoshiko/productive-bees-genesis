package com.ayoshiko.productivebeesgenesis.apiary;

/** Container contract used by the apiary screen's output page controls. */
public interface IPagedOutputContainer {

	int PREVIOUS_OUTPUT_PAGE_BUTTON = 10_001;
	int NEXT_OUTPUT_PAGE_BUTTON = 10_002;

	int getOutputPage();

	int getOutputPageCount();

	void setOutputPage(int page);

	default void changeOutputPage(int delta) {
		int pageCount = getOutputPageCount();
		if (pageCount <= 1) return;
		setOutputPage(Math.floorMod(getOutputPage() + delta, pageCount));
	}
}
