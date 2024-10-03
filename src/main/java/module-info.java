module com.efsavage.picknick {
	requires javafx.controls;
	requires javafx.fxml;
	requires java.desktop;
	requires metadata.extractor;


	opens com.efsavage.picknick to javafx.fxml;
	exports com.efsavage.picknick;
}