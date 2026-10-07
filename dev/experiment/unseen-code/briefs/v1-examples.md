
### How this codebase does this — the nearest working example, in full

Everything below is real code that compiles today, from `inventory-crud`. It is the closest existing implementation of what you have been asked to build. **Copy its shape** — the same annotations, the same imports, the same structure, the same sequence of calls — and put your own domain names in it. Do not work an API out from anywhere else: everything you need is used here.

#### To build `com.swarmcoder.demo.bookshelf.model.Book{long id; String title; String author; int year; ReadingStatus status; }`

The nearest existing code is `zerozstack-examples/inventory-crud/inventory-crud-shared/src/main/java/com/zeroz4j/example/model/Product.java` — annotated @DataModel, a class, with 19 members, in a `model` package, like this contract.

```java
// zerozstack-examples/inventory-crud/inventory-crud-shared/src/main/java/com/zeroz4j/example/model/Product.java
package com.zeroz4j.example.model;

import com.zeroz4j.api.DataModel;
import com.zeroz4j.api.validation.Min;
import com.zeroz4j.api.validation.NotBlank;
import com.zeroz4j.api.validation.Size;

import java.util.Objects;

@DataModel
public class Product {

    private long id;

    @NotBlank
    @Size(min = 1, max = 100)
    private String name;

    @NotBlank
    private String category;

    @Min(0)
    private int quantity;

    @Min(0)
    private double unitPrice;

    public Product() {
    }

    public Product(long id, String name, String category, int quantity, double unitPrice) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public double getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(double unitPrice) {
        this.unitPrice = unitPrice;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Product)) {
            return false;
        }
        Product other = (Product) o;
        return id == other.id
                && quantity == other.quantity
                && Double.compare(other.unitPrice, unitPrice) == 0
                && Objects.equals(name, other.name)
                && Objects.equals(category, other.category);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, category, quantity, unitPrice);
    }
}
```

#### To build `com.swarmcoder.demo.bookshelf.api.BookService{List<Book> list(); Book save(Book book); void delete(long id); }`

The nearest existing code is `zerozstack-examples/inventory-crud/inventory-crud-shared/src/main/java/com/zeroz4j/example/api/ProductService.java` — annotated @RmiService, an interface, with 3 members, in a `api` package, like this contract.

```java
// zerozstack-examples/inventory-crud/inventory-crud-shared/src/main/java/com/zeroz4j/example/api/ProductService.java
package com.zeroz4j.example.api;

import com.zeroz4j.api.RmiService;
import com.zeroz4j.example.model.Product;

import java.util.List;

/**
 * Service interface for warehouse inventory management.
 */
@RmiService
public interface ProductService {
    List<Product> list();
    Product save(Product p);
    void delete(long id);
}
```

#### To build `com.swarmcoder.demo.bookshelf.server.BookServiceImpl{List<Book> list(); Book save(Book book); void delete(long id); }`

The nearest existing code is `zerozstack-examples/inventory-crud/inventory-crud-server/src/main/java/com/zeroz4j/example/server/ProductServiceImpl.java` — annotated @ApplicationScoped, a class, with 4 members, in a `server` package, like this contract.

```java
// zerozstack-examples/inventory-crud/inventory-crud-server/src/main/java/com/zeroz4j/example/server/ProductServiceImpl.java
package com.zeroz4j.example.server;

import com.zeroz4j.example.api.ProductService;
import com.zeroz4j.example.model.Product;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.zeroz4j.db.net.ZeroZDbNode;

import java.util.List;

@ApplicationScoped
public class ProductServiceImpl implements ProductService {

    /**
     * The database node, not a raw storage manager.
     *
     * <p>Writes go through commands so they are atomic, and so the same code runs whether this
     * process owns the data ({@code zeroz4j.store.mode=EMBEDDED}) or talks to a database server
     * ({@code CLIENT}). Injecting {@code EmbeddedStorageManager} still works, but only where the
     * data is local, and it offers no transaction.</p>
     */
    @Inject
    private ZeroZDbNode db;

    @Override
    public List<Product> list() {
        db.execute(new ProductCommands.SeedIfEmpty());
        return db.query(new ProductQueries.ListAll());
    }

    @Override
    public Product save(Product p) {
        return p.getId() == 0
                ? db.execute(new ProductCommands.Create(p))
                : db.execute(new ProductCommands.Update(p));
    }

    @Override
    public void delete(long id) {
        db.execute(new ProductCommands.Delete(id));
    }
}
```

#### To build `com.swarmcoder.demo.bookshelf.client.BookshelfView{void render(); }`

The nearest existing code is `zerozstack-examples/inventory-crud/inventory-crud-client/src/main/java/com/zeroz4j/example/client/ExampleClientApp.java` — a class, with 2 members, in a `client` package, like this contract.

```java
// zerozstack-examples/inventory-crud/inventory-crud-client/src/main/java/com/zeroz4j/example/client/ExampleClientApp.java
package com.zeroz4j.example.client;

import com.zeroz4j.client.Zeroz4jClient;
import com.zeroz4j.api.RmiSecurityContext;
import org.teavm.jso.JSBody;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.html.HTMLElement;

public class ExampleClientApp {

    public static void main(String[] args) {
        String wsUrl = getWebSocketUrl();

        Zeroz4jClient.connect(wsUrl, () -> {
            // onResolved, not onAuthenticated: this example connects anonymously by design, so it
            // is never "authenticated" and would never mount. What it is waiting for is the server
            // having answered, which is what onResolved reports.
            //
            // On a green thread because building the view makes an RMI call, and this callback runs
            // on a stack that began in native JavaScript, where TeaVM cannot suspend a coroutine.
            RmiSecurityContext.onResolved(() -> new Thread(() -> {
                MainLayout mainLayout = new MainLayout();
                HTMLElement appRoot = Window.current().getDocument().getElementById("app-root");
                appRoot.appendChild(mainLayout.getElement());
            }).start());
        });
    }

    @JSBody(script =
        "var l = window.location;" +
        "var path = l.pathname;" +
        "var idx = path.lastIndexOf('/');" +
        "if (idx !== -1) { path = path.substring(0, idx + 1); } else { path = '/'; }" +
        "return (l.protocol === 'https:' ? 'wss://' : 'ws://') + l.host + path + 'wasm-rmi';")
    private static native String getWebSocketUrl();
}
```

#### The rest of that example — the files those use

```java
// zerozstack-examples/inventory-crud/inventory-crud-client/src/main/java/com/zeroz4j/example/client/DoubleField.java
package com.zeroz4j.example.client;

import com.zeroz4j.ui.component.AbstractField;
import com.zeroz4j.ui.component.mixin.HasColorVariants;
import com.zeroz4j.ui.component.mixin.HasSizeVariants;
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.html.HTMLInputElement;

public class DoubleField extends AbstractField<DoubleField, Double> implements
        HasColorVariants<DoubleField>,
        HasSizeVariants<DoubleField> {

    public DoubleField() {
        super("input", 0.0);
        getElement().setAttribute("type", "number");
        getElement().setAttribute("step", "0.01");
        addClassName("input");
        addClassName("input-bordered");

        EventListener<Event> inputListener = evt -> {
            HTMLInputElement input = getElement().cast();
            String valStr = input.getValue();
            try {
                double parsed = (valStr != null && !valStr.isEmpty()) ? Double.parseDouble(valStr) : 0.0;
                setModelValue(parsed, true);
            } catch (NumberFormatException e) {
                setModelValue(0.0, true);
            }
        };
        addDomEventListener("input", inputListener);
    }

    public DoubleField(String placeholder) {
        this();
        getElement().setAttribute("placeholder", placeholder);
    }

    @Override
    protected void setPresentationValue(Double value) {
        HTMLInputElement input = getElement().cast();
        String strVal = value != null ? String.valueOf(value) : "0.0";
        if (!strVal.equals(input.getValue())) {
            input.setValue(strVal);
        }
    }

    @Override
    public String getThemePrefix() {
        return "input";
    }
}
```

```java
// zerozstack-examples/inventory-crud/inventory-crud-client/src/main/java/com/zeroz4j/example/client/IntegerField.java
package com.zeroz4j.example.client;

import com.zeroz4j.ui.component.AbstractField;
import com.zeroz4j.ui.component.mixin.HasColorVariants;
import com.zeroz4j.ui.component.mixin.HasSizeVariants;
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.html.HTMLInputElement;

public class IntegerField extends AbstractField<IntegerField, Integer> implements
        HasColorVariants<IntegerField>,
        HasSizeVariants<IntegerField> {

    public IntegerField() {
        super("input", 0);
        getElement().setAttribute("type", "number");
        addClassName("input");
        addClassName("input-bordered");

        EventListener<Event> inputListener = evt -> {
            HTMLInputElement input = getElement().cast();
            String valStr = input.getValue();
            try {
                int parsed = (valStr != null && !valStr.isEmpty()) ? Integer.parseInt(valStr) : 0;
                setModelValue(parsed, true);
            } catch (NumberFormatException e) {
                setModelValue(0, true);
            }
        };
        addDomEventListener("input", inputListener);
    }

    public IntegerField(String placeholder) {
        this();
        getElement().setAttribute("placeholder", placeholder);
    }

    @Override
    protected void setPresentationValue(Integer value) {
        HTMLInputElement input = getElement().cast();
        String strVal = value != null ? String.valueOf(value) : "0";
        if (!strVal.equals(input.getValue())) {
            input.setValue(strVal);
        }
    }

    @Override
    public String getThemePrefix() {
        return "input";
    }
}
```

```java
// zerozstack-examples/inventory-crud/inventory-crud-client/src/main/java/com/zeroz4j/example/client/InventoryView.java
package com.zeroz4j.example.client;

import com.zeroz4j.api.Disposable;
import com.zeroz4j.example.api.ProductService;
import com.zeroz4j.example.api.ProductService_Stub;
import com.zeroz4j.example.model.Product;
import com.zeroz4j.example.model.Product_Rules;
import com.zeroz4j.signals.Computed;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;
import com.zeroz4j.ui.component.*;
import com.zeroz4j.ui.layout.*;

import java.util.ArrayList;
import java.util.List;

public class InventoryView extends Card {

    private final ProductService productService = new ProductService_Stub();

    // Source signals
    private final ValueSignal<List<Product>> products = new ValueSignal<>(new ArrayList<>());
    private final ValueSignal<String> filter = new ValueSignal<>("");
    private final ValueSignal<Long> selectedProductId = new ValueSignal<>(0L);

    // Form field signals
    private final ValueSignal<String> formName = new ValueSignal<>("");
    private final ValueSignal<String> formCategory = new ValueSignal<>("Electronics");
    private final ValueSignal<Integer> formQuantity = new ValueSignal<>(0);
    private final ValueSignal<Double> formUnitPrice = new ValueSignal<>(0.0);

    // Status message signals
    private final ValueSignal<String> statusMessage = new ValueSignal<>("");
    private final ValueSignal<Boolean> statusSuccess = new ValueSignal<>(true);

    // Derived signals
    private final Computed<List<Product>> filteredProducts = new Computed<>(() -> {
        List<Product> list = products.get();
        String query = filter.get();
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>(list);
        }
        String lowerQuery = query.trim().toLowerCase();
        List<Product> result = new ArrayList<>();
        for (Product p : list) {
            boolean matchesName = p.getName() != null && p.getName().toLowerCase().contains(lowerQuery);
            boolean matchesCat = p.getCategory() != null && p.getCategory().toLowerCase().contains(lowerQuery);
            if (matchesName || matchesCat) {
                result.add(p);
            }
        }
        return result;
    });

    private final Computed<Integer> totalProductCount = new Computed<>(() -> products.get().size());

    private final Computed<Integer> totalStockItems = new Computed<>(() -> {
        int total = 0;
        for (Product p : products.get()) {
            total += p.getQuantity();
        }
        return total;
    });

    private final Computed<Double> totalStockValue = new Computed<>(() -> {
        double total = 0.0;
        for (Product p : products.get()) {
            total += p.getQuantity() * p.getUnitPrice();
        }
        return total;
    });

    private final List<Disposable> disposables = new ArrayList<>();

    public InventoryView() {
        super();
        addClassName("w-full");
        addClassName("max-w-7xl");
        addClassName("mx-auto");
        addClassName("flex");
        addClassName("flex-col");
        addClassName("gap-4");

        add(new CardTitle("Warehouse Inventory Management"));

        // Status alert container
        Div statusDiv = new Div();
        add(statusDiv);
        disposables.add(Effect.create(() -> {
            String msg = statusMessage.get();
            statusDiv.removeAll();
            if (msg != null && !msg.trim().isEmpty()) {
                Alert alert = Boolean.TRUE.equals(statusSuccess.get())
                        ? Alert.success(msg)
                        : Alert.danger(msg);
                statusDiv.add(alert);
            }
        }));

        // Header KPI summary cards
        HorizontalLayout statsLayout = new HorizontalLayout();
        statsLayout.addClassName("gap-4");
        statsLayout.addClassName("w-full");
        statsLayout.addClassName("mb-2");

        KpiTile totalProductsTile = new KpiTile("Total Products");
        KpiTile totalItemsTile = new KpiTile("Total In-Stock Items");
        KpiTile totalValueTile = new KpiTile("Total Inventory Value");

        statsLayout.add(totalProductsTile, totalItemsTile, totalValueTile);
        add(statsLayout);

        disposables.add(Effect.create(() -> {
            totalProductsTile.value(String.valueOf(totalProductCount.get()));
            totalItemsTile.value(String.valueOf(totalStockItems.get()));
            totalValueTile.value("$" + String.format("%.2f", totalStockValue.get()));
        }));

        // Main Split Content: Master (Left) and Detail (Right)
        HorizontalLayout mainSplit = new HorizontalLayout();
        mainSplit.addClassName("gap-6");
        mainSplit.addClassName("w-full");
        mainSplit.addClassName("items-start");

        // --- MASTER PANEL (Left) ---
        VerticalLayout masterPanel = new VerticalLayout();
        masterPanel.addClassName("w-7/12");
        masterPanel.addClassName("gap-3");

        // Filter / Search Input
        TextField filterField = new TextField("Filter products by name or category...");
        filterField.addClassName("w-full");
        filterField.bindValue(filter);
        masterPanel.add(filterField);

        // Products List / Table Container
        Div tableContainer = new Div();
        tableContainer.addClassName("w-full");
        tableContainer.addClassName("overflow-x-auto");
        tableContainer.addClassName("bg-base-200");
        tableContainer.addClassName("rounded-box");
        tableContainer.addClassName("p-2");
        masterPanel.add(tableContainer);

        disposables.add(Effect.create(() -> renderProductTable(tableContainer)));

        mainSplit.add(masterPanel);

        // --- DETAIL PANEL (Right) ---
        Card detailCard = new Card();
        detailCard.addClassName("w-5/12");
        detailCard.addClassName("bg-base-200");

        CardTitle detailTitle = new CardTitle("Product Details");
        detailCard.add(detailTitle);

        FormLayout formLayout = new FormLayout();
        formLayout.addClassName("gap-3");

        // 1. Name field
        VerticalLayout nameGroup = new VerticalLayout();
        nameGroup.addClassName("gap-1");
        Span nameLabel = new Span("Product Name *");
        nameLabel.addClassName("font-semibold");
        nameLabel.addClassName("text-sm");
        TextField nameField = new TextField("Enter product name");
        nameField.bindValue(formName);
        nameField.withRule(Product_Rules.name());
        Div nameError = new Div();
        nameError.addClassName("text-error");
        nameError.addClassName("text-xs");
        nameGroup.add(nameLabel, nameField, nameError);
        disposables.add(Effect.create(() -> {
            formName.get();
            List<String> violations = nameField.getViolations();
            nameError.setText(violations.isEmpty() ? "" : violations.get(0));
        }));
        formLayout.add(nameGroup);

        // 2. Category field
        VerticalLayout catGroup = new VerticalLayout();
        catGroup.addClassName("gap-1");
        Span catLabel = new Span("Category *");
        catLabel.addClassName("font-semibold");
        catLabel.addClassName("text-sm");
        Select categorySelect = new Select();
        categorySelect.setItems(List.of("Electronics", "Furniture", "Supplies", "Clothing", "Food", "Other"));
        categorySelect.bindValue(formCategory);
        categorySelect.withRule(Product_Rules.category());
        Div catError = new Div();
        catError.addClassName("text-error");
        catError.addClassName("text-xs");
        catGroup.add(catLabel, categorySelect, catError);
        disposables.add(Effect.create(() -> {
            formCategory.get();
            List<String> violations = categorySelect.getViolations();
            catError.setText(violations.isEmpty() ? "" : violations.get(0));
        }));
        formLayout.add(catGroup);

        // 3. Quantity field
        VerticalLayout qtyGroup = new VerticalLayout();
        qtyGroup.addClassName("gap-1");
        Span qtyLabel = new Span("Quantity in Stock *");
        qtyLabel.addClassName("font-semibold");
        qtyLabel.addClassName("text-sm");
        IntegerField quantityField = new IntegerField("0");
        quantityField.bindValue(formQuantity);
        quantityField.withRule(Product_Rules.quantity());
        Div qtyError = new Div();
        qtyError.addClassName("text-error");
        qtyError.addClassName("text-xs");
        qtyGroup.add(qtyLabel, quantityField, qtyError);
        disposables.add(Effect.create(() -> {
            formQuantity.get();
            List<String> violations = quantityField.getViolations();
            qtyError.setText(violations.isEmpty() ? "" : violations.get(0));
        }));
        formLayout.add(qtyGroup);

        // 4. Unit Price field
        VerticalLayout priceGroup = new VerticalLayout();
        priceGroup.addClassName("gap-1");
        Span priceLabel = new Span("Unit Price ($) *");
        priceLabel.addClassName("font-semibold");
        priceLabel.addClassName("text-sm");
        DoubleField priceField = new DoubleField("0.00");
        priceField.bindValue(formUnitPrice);
        priceField.withRule(Product_Rules.unitPrice());
        Div priceError = new Div();
        priceError.addClassName("text-error");
        priceError.addClassName("text-xs");
        priceGroup.add(priceLabel, priceField, priceError);
        disposables.add(Effect.create(() -> {
            formUnitPrice.get();
            List<String> violations = priceField.getViolations();
            priceError.setText(violations.isEmpty() ? "" : violations.get(0));
        }));
        formLayout.add(priceGroup);

        detailCard.add(formLayout);

        // Form validity computed
        Computed<Boolean> formValid = new Computed<>(() -> {
            formName.get();
            formCategory.get();
            formQuantity.get();
            formUnitPrice.get();

            return nameField.isValid()
                    && categorySelect.isValid()
                    && quantityField.isValid()
                    && priceField.isValid();
        });

        // Detail Action Buttons
        HorizontalLayout buttonRow = new HorizontalLayout();
        buttonRow.addClassName("mt-4");
        buttonRow.addClassName("gap-2");

        Button newButton = new Button("New");
        newButton.addClassName("btn-secondary");
        newButton.addClassName("btn-sm");
        newButton.addClickListener(e -> resetForm());

        Button saveButton = new Button("Save");
        saveButton.addClassName("btn-primary");
        saveButton.addClassName("btn-sm");

        Button deleteButton = new Button("Delete");
        deleteButton.addClassName("btn-error");
        deleteButton.addClassName("btn-sm");

        buttonRow.add(newButton, saveButton, deleteButton);
        detailCard.add(buttonRow);

        // Reactively enable/disable buttons based on selection and form validity
        disposables.add(Effect.create(() -> {
            boolean valid = Boolean.TRUE.equals(formValid.get());
            saveButton.setEnabled(valid);
        }));

        disposables.add(Effect.create(() -> {
            long selId = selectedProductId.get();
            deleteButton.setEnabled(selId > 0);
            if (selId == 0) {
                detailTitle.setText("New Product");
            } else {
                detailTitle.setText("Edit Product #" + selId);
            }
        }));

        // Save Action
        saveButton.addClickListener(e -> {
            if (!Boolean.TRUE.equals(formValid.get())) {
                return;
            }
            long id = selectedProductId.get();
            Product p = new Product(
                    id,
                    formName.get() != null ? formName.get().trim() : "",
                    formCategory.get() != null ? formCategory.get().trim() : "Electronics",
                    formQuantity.get() != null ? formQuantity.get() : 0,
                    formUnitPrice.get() != null ? formUnitPrice.get() : 0.0
            );

            try {
                Product saved = productService.save(p);
                statusMessage.set("Product saved successfully: " + saved.getName() + " (ID #" + saved.getId() + ")");
                statusSuccess.set(true);

                // Update client products signal immutably with the saved product
                products.update(current -> {
                    List<Product> next = new ArrayList<>();
                    boolean found = false;
                    for (Product item : current) {
                        if (item.getId() == saved.getId()) {
                            next.add(saved);
                            found = true;
                        } else {
                            next.add(item);
                        }
                    }
                    if (!found) {
                        next.add(saved);
                    }
                    return next;
                });
                selectedProductId.set(saved.getId());
            } catch (Exception ex) {
                statusMessage.set("Failed to save product: " + ex.getMessage());
                statusSuccess.set(false);
            }
        });

        // Delete Action
        deleteButton.addClickListener(e -> {
            long id = selectedProductId.get();
            if (id <= 0) {
                return;
            }
            try {
                productService.delete(id);
                statusMessage.set("Product #" + id + " deleted.");
                statusSuccess.set(true);

                products.update(current -> {
                    List<Product> next = new ArrayList<>();
                    for (Product item : current) {
                        if (item.getId() != id) {
                            next.add(item);
                        }
                    }
                    return next;
                });
                resetForm();
            } catch (Exception ex) {
                statusMessage.set("Failed to delete product: " + ex.getMessage());
                statusSuccess.set(false);
            }
        });

        mainSplit.add(detailCard);
        add(mainSplit);

        // Fetch initial list
        loadProducts();
    }

    private void renderProductTable(Div container) {
        container.removeAll();
        List<Product> list = filteredProducts.get();

        if (list == null || list.isEmpty()) {
            EmptyState emptyState = new EmptyState(
                    "inbox",
                    "No Products Found",
                    "No matching inventory products exist. Add a new product or clear the search filter."
            );
            container.add(emptyState);
            return;
        }

        Table table = new Table();
        table.addClassName("table");
        table.addClassName("table-zebra");
        table.addClassName("w-full");

        Component thead = new Component("thead") {};
        Component headerRow = new Component("tr") {};

        for (String col : new String[]{"ID", "Name", "Category", "Quantity", "Price", "Action"}) {
            Component th = new Component("th") {};
            th.getElement().setTextContent(col);
            headerRow.getElement().appendChild(th.getElement());
        }
        thead.getElement().appendChild(headerRow.getElement());
        table.getElement().appendChild(thead.getElement());

        Component tbody = new Component("tbody") {};
        long currentSelected = selectedProductId.get();

        for (Product p : list) {
            Component tr = new Component("tr") {};
            String cssClass = "cursor-pointer" + (p.getId() == currentSelected ? " bg-primary/20" : "");
            tr.getElement().setAttribute("class", cssClass);
            tr.addDomEventListener("click", evt -> selectProduct(p));

            addTd(tr, "#" + p.getId());
            addTd(tr, p.getName());
            addTd(tr, p.getCategory());
            addTd(tr, String.valueOf(p.getQuantity()));
            addTd(tr, "$" + String.format("%.2f", p.getUnitPrice()));

            Component tdAction = new Component("td") {};
            Button selectBtn = new Button("Edit");
            selectBtn.addClassName("btn-xs");
            selectBtn.addClassName("btn-outline");
            selectBtn.addClickListener(e -> selectProduct(p));
            tdAction.getElement().appendChild(selectBtn.getElement());
            tr.getElement().appendChild(tdAction.getElement());

            tbody.getElement().appendChild(tr.getElement());
        }
        table.getElement().appendChild(tbody.getElement());
        container.add(table);
    }

    private void addTd(Component tr, String text) {
        Component td = new Component("td") {};
        td.getElement().setTextContent(text != null ? text : "");
        tr.getElement().appendChild(td.getElement());
    }

    private void selectProduct(Product p) {
        selectedProductId.set(p.getId());
        formName.set(p.getName());
        formCategory.set(p.getCategory() != null ? p.getCategory() : "Electronics");
        formQuantity.set(p.getQuantity());
        formUnitPrice.set(p.getUnitPrice());
    }

    private void resetForm() {
        selectedProductId.set(0L);
        formName.set("");
        formCategory.set("Electronics");
        formQuantity.set(0);
        formUnitPrice.set(0.0);
    }

    private void loadProducts() {
        try {
            List<Product> list = productService.list();
            products.set(new ArrayList<>(list));
            statusMessage.set("");
        } catch (Exception ex) {
            statusMessage.set("Failed to load inventory products: " + ex.getMessage());
            statusSuccess.set(false);
        }
    }

    public void dispose() {
        for (Disposable disposable : disposables) {
            disposable.dispose();
        }
        disposables.clear();
        filteredProducts.dispose();
        totalProductCount.dispose();
        totalStockItems.dispose();
        totalStockValue.dispose();
    }
}
```

```java
// zerozstack-examples/inventory-crud/inventory-crud-client/src/main/java/com/zeroz4j/example/client/MainLayout.java
package com.zeroz4j.example.client;

import com.zeroz4j.ui.component.Component;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.HorizontalLayout;
import com.zeroz4j.ui.layout.Span;
import com.zeroz4j.ui.layout.VerticalLayout;
import com.zeroz4j.ui.component.Menu;
import com.zeroz4j.ui.component.ThemeController;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;
import org.teavm.jso.browser.Window;

/**
 * Main application layout. The theme toggle is bound to a signal via
 * {@code bindValue} — components stay in sync with signal state in both directions.
 */
public class MainLayout extends HorizontalLayout {

    private final Component inventoryView = new InventoryView();

    public MainLayout() {
        super();
        addClassName("h-screen");
        addClassName("w-screen");
        addClassName("bg-base-100");
        addClassName("text-base-content");
        addClassName("flex");

        // --- Sidebar ---
        VerticalLayout sidebar = new VerticalLayout();
        sidebar.addClassName("w-64");
        sidebar.addClassName("bg-base-200");
        sidebar.addClassName("h-full");
        sidebar.addClassName("p-0");
        sidebar.addClassName("flex-shrink-0");
        sidebar.addClassName("overflow-y-auto");
        sidebar.addClassName("overflow-x-hidden");

        Menu menu = new Menu();
        menu.addClassName("h-full");
        menu.addClassName("w-full");
        menu.addClassName("rounded-none");
        menu.addClassName("flex-col");
        
        menu.addTitle("zeroz inventory");

        // Theme Toggle
        Component themeItem = new Component("li") {};
        HorizontalLayout themeLayout = new HorizontalLayout();
        themeLayout.addClassName("px-4");
        themeLayout.addClassName("py-4");
        themeLayout.addClassName("mt-auto");
        themeLayout.addClassName("justify-between");
        
        Span themeLabel = new Span("Dark Mode");
        themeLayout.add(themeLabel);
        
        ThemeController themeToggle = new ThemeController(true);
        ValueSignal<Boolean> darkTheme = new ValueSignal<>(true);
        themeToggle.bindValue(darkTheme);

        Effect.create(() -> {
            Boolean isDark = darkTheme.get();
            Window.current().getDocument().getBody()
                    .setAttribute("data-theme", isDark != null && isDark ? "dark" : "light");
        });

        themeLayout.add(themeToggle);
        themeItem.getElement().appendChild(themeLayout.getElement());
        menu.add(themeItem);

        sidebar.add(menu);

        add(sidebar);

        // --- Content Area ---
        Div contentArea = new Div();
        contentArea.addClassName("flex-1");
        contentArea.addClassName("p-8");
        contentArea.addClassName("overflow-y-auto");

        contentArea.add(inventoryView);

        add(contentArea);
    }
}
```

```java
// zerozstack-examples/inventory-crud/inventory-crud-client/src/test/java/com/zeroz4j/example/client/ExampleClientAppTest.java
package com.zeroz4j.example.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExampleClientAppTest {

    @Test
    public void testClientInitialization() {
        // Placeholder test to verify test suite configuration
        assertTrue(true);
    }
}
```

```java
// zerozstack-examples/inventory-crud/inventory-crud-server/src/main/java/com/zeroz4j/example/server/ExampleServer.java
package com.zeroz4j.example.server;

import com.zeroz4j.server.Zeroz4jServer;

/**
 * Embeds the zeroz4j RMI backend (CDI via Helidon MP) inside the JVM.
 */
public final class ExampleServer {

    /**
     * The port this example serves on when nothing says otherwise.
     *
     * <p>Every example has a number of its own, so two of them started at the same time do not
     * fight over one address. Move this one somewhere else without editing the file: put
     * {@code --port 8099} on the command line, or start the JVM with {@code -Dzeroz.port=8099}.</p>
     */
    private static final int DEFAULT_PORT = 8089;

    public static void main(String[] args) {
        Zeroz4jServer.start(port(args), "zeroz4j Inventory CRUD Server").join();
    }

    /**
     * Works out which port to listen on.
     *
     * <p>In order: {@code --port <number>} on the command line, then the {@code zeroz.port} system
     * property, then {@link #DEFAULT_PORT}.</p>
     *
     * @param args the command line this server was started with
     * @return the port to bind
     */
    private static int port(String[] args) {
        if (args != null) {
            for (int i = 0; i + 1 < args.length; i++) {
                if ("--port".equals(args[i])) {
                    return Integer.parseInt(args[i + 1].trim());
                }
            }
        }
        String configured = System.getProperty("zeroz.port", "").trim();
        return configured.isEmpty() ? DEFAULT_PORT : Integer.parseInt(configured);
    }
}
```

```java
// zerozstack-examples/inventory-crud/inventory-crud-server/src/main/java/com/zeroz4j/example/server/ProductCommands.java
package com.zeroz4j.example.server;

import com.zeroz4j.example.model.Product;
import com.zeroz4j.example.server.store.DataRoot;
import com.zeroz4j.db.WriteContext;
import com.zeroz4j.db.net.DbCommand;

/**
 * Writes expressed as commands.
 *
 * <p>Each one runs inside a single atomic transaction: everything it enlists is committed
 * together or not at all. That is what makes the id counter and the product list impossible to
 * separate — the bug this example used to have, where a crash between two saves persisted the
 * product and lost the counter that named it.</p>
 *
 * <p>These are plain classes with public fields and no-arg constructors, <b>not records</b>.
 * EclipseStore's serializer reaches fields directly and the JVM refuses that for records unless
 * started with {@code --add-exports java.base/jdk.internal.misc=ALL-UNNAMED}. A record fails at
 * the first remote call rather than at compile time, so it looks like a networking fault.</p>
 *
 * <p>Because they are commands rather than lambdas, they execute wherever the data lives: in this
 * process when {@code zeroz4j.store.mode=EMBEDDED}, on the database server when {@code CLIENT}.
 * The service code above does not change either way.</p>
 */
public final class ProductCommands {

    /** Allocates an id and inserts the product in one commit. */
    public static final class Create implements DbCommand<Product> {
        public Product product;

        public Create() {
        }

        public Create(Product product) {
            this.product = product;
        }

        @Override
        public Product execute(WriteContext ctx, Object root) {
            DataRoot data = (DataRoot) root;
            ctx.edit(data);                 // the id counter is about to change
            ctx.edit(data.getProducts());   // and so is the list

            long newId = data.getNextId() <= 0 ? 1 : data.getNextId();
            product.setId(newId);
            data.setNextId(newId + 1);
            data.getProducts().add(product);
            return product;
        }
    }

    /** Updates a product in place. */
    public static final class Update implements DbCommand<Product> {
        public Product product;

        public Update() {
        }

        public Update(Product product) {
            this.product = product;
        }

        @Override
        public Product execute(WriteContext ctx, Object root) {
            DataRoot data = (DataRoot) root;
            for (Product existing : data.getProducts()) {
                if (existing.getId() == product.getId()) {
                    ctx.edit(existing);
                    existing.setName(product.getName());
                    existing.setCategory(product.getCategory());
                    existing.setQuantity(product.getQuantity());
                    existing.setUnitPrice(product.getUnitPrice());
                    break;
                }
            }
            // The list itself is unchanged; only a member was edited. Enlisting the member is
            // enough, and enlisting the list as well would be harmless but misleading.
            return product;
        }
    }

    public static final class Delete implements DbCommand<Boolean> {
        public long id;

        public Delete() {
        }

        public Delete(long id) {
            this.id = id;
        }

        @Override
        public Boolean execute(WriteContext ctx, Object root) {
            DataRoot data = (DataRoot) root;
            ctx.edit(data.getProducts());
            return data.getProducts().removeIf(p -> p.getId() == id);
        }
    }

    /** Seeds the catalogue on first use — again, list and counter in one commit. */
    public static final class SeedIfEmpty implements DbCommand<Integer> {
        @Override
        public Integer execute(WriteContext ctx, Object root) {
            DataRoot data = (DataRoot) root;
            if (!data.getProducts().isEmpty()) {
                return 0;
            }
            ctx.edit(data);
            ctx.edit(data.getProducts());

            long nextId = data.getNextId() <= 0 ? 1 : data.getNextId();
            data.getProducts().add(new Product(nextId++, "Wireless Ergonomic Mouse", "Electronics", 45, 29.99));
            data.getProducts().add(new Product(nextId++, "Electric Standing Desk", "Furniture", 12, 349.50));
            data.getProducts().add(new Product(nextId++, "USB-C Multi-Port Hub", "Electronics", 80, 49.95));
            data.getProducts().add(new Product(nextId++, "Ergonomic Mesh Chair", "Furniture", 18, 199.00));
            data.setNextId(nextId);
            return 4;
        }
    }

    private ProductCommands() {
    }
}
```

```java
// zerozstack-examples/inventory-crud/inventory-crud-server/src/main/java/com/zeroz4j/example/server/ProductQueries.java
package com.zeroz4j.example.server;

import com.zeroz4j.example.model.Product;
import com.zeroz4j.example.server.store.DataRoot;
import com.zeroz4j.db.net.DbQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads expressed as queries, so they run wherever the data lives.
 *
 * <p>A query returns a value, never a live graph node: whatever it returns is serialized back to
 * the caller, so returning a deeply-connected entity would ship its reachable graph. Copying into
 * a new list is deliberate.</p>
 */
public final class ProductQueries {

    public static final class ListAll implements DbQuery<List<Product>> {
        @Override
        public List<Product> execute(Object root) {
            return new ArrayList<>(((DataRoot) root).getProducts());
        }
    }

    private ProductQueries() {
    }
}
```

```java
// zerozstack-examples/inventory-crud/inventory-crud-server/src/main/java/com/zeroz4j/example/server/WebSocketConfig.java
package com.zeroz4j.example.server;

import jakarta.websocket.Endpoint;
import jakarta.websocket.server.ServerApplicationConfig;
import jakarta.websocket.server.ServerEndpointConfig;
import com.zeroz4j.server.WasmRmiServerEngine;
import java.util.Collections;
import java.util.Set;

public class WebSocketConfig implements ServerApplicationConfig {
    @Override
    public Set<ServerEndpointConfig> getEndpointConfigs(Set<Class<? extends Endpoint>> endpointClasses) {
        return Collections.emptySet();
    }
    
    @Override
    public Set<Class<?>> getAnnotatedEndpointClasses(Set<Class<?>> scanned) {
        return Collections.singleton(WasmRmiServerEngine.class);
    }
}
```

```java
// zerozstack-examples/inventory-crud/inventory-crud-server/src/main/java/com/zeroz4j/example/server/store/DataRoot.java
package com.zeroz4j.example.server.store;

import com.zeroz4j.example.model.Product;
import jakarta.enterprise.inject.Vetoed;

import java.util.ArrayList;
import java.util.List;

@Vetoed
public class DataRoot {
    private List<Product> products = new ArrayList<>();
    private long nextId = 1;

    public List<Product> getProducts() {
        return products;
    }

    public void setProducts(List<Product> products) {
        this.products = products;
    }

    public long getNextId() {
        return nextId;
    }

    public void setNextId(long nextId) {
        this.nextId = nextId;
    }
}
```

```java
// zerozstack-examples/inventory-crud/inventory-crud-server/src/main/java/com/zeroz4j/example/server/store/DefaultDataRootProvider.java
package com.zeroz4j.example.server.store;

import com.zeroz4j.api.store.DataRootProvider;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DefaultDataRootProvider implements DataRootProvider {
    @Override
    public Object createDefaultRoot(String tenantId) {
        return new DataRoot();
    }
}
```

```java
// zerozstack-examples/inventory-crud/inventory-crud-server/src/main/java/com/zeroz4j/example/server/store/DefaultTenantResolver.java
package com.zeroz4j.example.server.store;

import com.zeroz4j.api.store.TenantResolver;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DefaultTenantResolver implements TenantResolver {
    @Override
    public String resolveTenant() {
        return "default";
    }
}
```

```java
// zerozstack-examples/inventory-crud/inventory-crud-server/src/test/java/com/zeroz4j/example/server/ProductServiceImplTest.java
package com.zeroz4j.example.server;

import com.zeroz4j.db.net.ZeroZDbNode;
import com.zeroz4j.example.model.Product;
import com.zeroz4j.example.server.store.DataRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the service against a real store rather than a mock.
 *
 * <p>The previous version recorded calls to a fake storage manager and asserted that the product
 * list and the id counter were written in the <em>same</em> call, because two calls meant two
 * commits and a crash between them lost the counter. That hazard no longer exists to test for: a
 * command commits everything it enlists atomically or nothing at all, so the property is now
 * structural. What is worth testing is the behaviour a user relies on — and this checks it by
 * reopening the store, which a mock could never do.</p>
 */
class ProductServiceImplTest {

    @TempDir
    Path storeDir;

    private ZeroZDbNode db;
    private ProductServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        db = ZeroZDbNode.embedded(storeDir.resolve("store"), DataRoot::new);
        service = new ProductServiceImpl();
        inject(service, db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    private static void inject(ProductServiceImpl target, ZeroZDbNode node) throws Exception {
        Field field = ProductServiceImpl.class.getDeclaredField("db");
        field.setAccessible(true);
        field.set(target, node);
    }

    @Test
    void listSeedsTheCatalogueOnFirstUseAndNotAgain() {
        assertEquals(4, service.list().size(), "first call seeds the catalogue");
        assertEquals(4, service.list().size(), "seeding must not repeat");
    }

    @Test
    void savingANewProductAssignsAnId() {
        service.list();
        Product saved = service.save(new Product(0, "Desk Lamp", "Furniture", 5, 19.99));
        assertNotEquals(0, saved.getId(), "a new product is given an id");
    }

    @Test
    void twoSavesDoNotReuseAnId() {
        service.list();
        long first = service.save(new Product(0, "A", "X", 1, 1.0)).getId();
        long second = service.save(new Product(0, "B", "X", 1, 1.0)).getId();
        assertNotEquals(first, second, "the id counter advanced with the insert");
    }

    @Test
    void anUpdateChangesTheProductInPlace() {
        service.list();
        Product saved = service.save(new Product(0, "Old name", "X", 1, 1.0));

        service.save(new Product(saved.getId(), "New name", "X", 2, 2.0));

        Product found = service.list().stream()
                .filter(p -> p.getId() == saved.getId())
                .findFirst().orElseThrow();
        assertEquals("New name", found.getName());
        assertEquals(2, found.getQuantity());
    }

    @Test
    void deleteRemovesTheProduct() {
        service.list();
        Product saved = service.save(new Product(0, "Doomed", "X", 1, 1.0));

        service.delete(saved.getId());

        assertFalse(service.list().stream().anyMatch(p -> p.getId() == saved.getId()));
    }

    /**
     * The property the old mock-based assertions were really reaching for: after a restart, the
     * product and the counter that named it are both present. A save that persisted one without
     * the other would hand out a duplicate id here.
     */
    @Test
    void productsAndTheIdCounterSurviveAReopen() throws Exception {
        service.list();
        long firstId = service.save(new Product(0, "Persisted", "X", 1, 1.0)).getId();
        int countBefore = service.list().size();
        db.close();

        db = ZeroZDbNode.embedded(storeDir.resolve("store"), DataRoot::new);
        service = new ProductServiceImpl();
        inject(service, db);

        assertEquals(countBefore, service.list().size(), "the catalogue survived the restart");
        long nextId = service.save(new Product(0, "After restart", "X", 1, 1.0)).getId();
        assertTrue(nextId > firstId, "the counter survived too, so ids are not reused");
    }
}
```

#### `bookshelf-demo-server/pom.xml` does not declare what that example uses

The example's `inventory-crud-server/pom.xml` declares these and yours does not. Add the ones your code needs — the version is managed by the parent, so no `<version>` element:

- `com.zeroz4j:zerozstack-store-eclipsestore`
- `org.junit.jupiter:junit-jupiter-api`
- `org.junit.jupiter:junit-jupiter-engine`
