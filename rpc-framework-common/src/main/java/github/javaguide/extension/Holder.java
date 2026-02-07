package github.javaguide.extension;

/**
 * 装“单例对象”的容器
 * 通过volatile关键字保证“单例对象”只有在构造完成之后，才能被其他线程看到
 * 
 */
public class Holder<T> {

    private volatile T value;

    public T get() {
        return value;
    }

    public void set(T value) {
        this.value = value;
    }
}
