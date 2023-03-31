package test.org.springdoc.api.v30.app204;

import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.customizers.OpenApiCustomiser;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.tags.Tag;


public class OrderDemo {

	@RestController
	public static class MyController {

		@GetMapping("/test/{*param}")
		public String testingMethod(@PathVariable("param") String param) {
			return "foo";
		}
	}

}