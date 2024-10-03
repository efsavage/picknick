module com.efsavage.picknick.picknick {
	requires javafx.controls;
	requires javafx.fxml;
	requires java.desktop;


	opens com.efsavage.picknick.picknick to javafx.fxml;
	exports com.efsavage.picknick;
	opens com.efsavage.picknick to javafx.fxml;
}