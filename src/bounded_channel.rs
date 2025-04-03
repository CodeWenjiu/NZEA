use std::{cell::RefCell, collections::VecDeque, rc::Rc};

pub struct BoundedChannel<T> {
    queue: Rc<RefCell<VecDeque<T>>>,
    capacity: usize,
}

impl<T> BoundedChannel<T> {
    pub fn new(capacity: usize) -> Self {
        Self {
            queue: Rc::new(RefCell::new(VecDeque::with_capacity(capacity))),
            capacity,
        }
    }

    pub fn send(&self, message: T) -> Result<(), String> {
        let mut queue = self.queue.borrow_mut();
        if queue.len() >= self.capacity {
            return Err("Queue is full".to_string());
        }
        queue.push_back(message);
        Ok(())
    }

    pub fn recv(&self) -> Option<T> {
        let mut queue = self.queue.borrow_mut();
        queue.pop_front()
    }

    pub fn flush(&self) {
        let mut queue = self.queue.borrow_mut();
        queue.clear();
    }
}
